/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gpukernel

import groovy.transform.CompileStatic
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeExpressionTransformer
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.*
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.Types
import org.codehaus.groovy.transform.AbstractASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation

import static org.codehaus.groovy.ast.ClassHelper.*
import static org.codehaus.groovy.ast.tools.GeneralUtils.castX
import static org.codehaus.groovy.ast.tools.GeneralUtils.constX

/**
 * Rewrites a kernel method before static type checking so that it compiles like javac would:
 * <ul>
 *   <li>{@code float op float} (or float op int) is narrowed straight back to float, which is
 *   bit-for-bit what javac's {@code fmul}/{@code fadd} etc. would compute</li>
 *   <li>float literals become {@code (float) <double literal>} so Groovy doesn't hoist them into
 *   synthetic {@code $const$} fields, which kernel compilers can't read</li>
 *   <li>static final primitive constants are inlined</li>
 * </ul>
 * Types are inferred locally and conservatively: anything not obviously float/int is left alone.
 */
@CompileStatic
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
class GpuKernelTransform extends AbstractASTTransformation {
    private static final Set<Integer> ARITH = [Types.PLUS, Types.MINUS, Types.MULTIPLY, Types.DIVIDE] as Set

    void visit(ASTNode[] nodes, SourceUnit source) {
        init(nodes, source)
        def method = (MethodNode) nodes[1]
        new ClassCodeExpressionTransformer() {
            protected SourceUnit getSourceUnit() { source }

            Expression transform(Expression e) {
                if (e instanceof ConstantExpression && isFloat(e.type)) {
                    return floatLiteral(((Number) e.value).floatValue())
                }
                if (e instanceof VariableExpression) {
                    def inlined = inlineConstant(e)
                    if (inlined) return inlined
                }
                if (e instanceof DeclarationExpression && !e.multipleAssignmentDeclaration
                        && isFloat(e.variableExpression.originType)
                        && e.rightExpression instanceof ConstantExpression
                        && ((ConstantExpression) e.rightExpression).value instanceof Number) {
                    e.rightExpression = floatLiteral(((Number) ((ConstantExpression) e.rightExpression).value).floatValue())
                    return e
                }
                if (e instanceof BinaryExpression && !(e instanceof DeclarationExpression)
                        && e.operation.type in ARITH) {
                    def left = transform(e.leftExpression)
                    def right = transform(e.rightExpression)
                    def lt = typeOf(left), rt = typeOf(right)
                    if ((isFloat(lt) || isFloat(rt)) && isIntOrFloat(lt) && isIntOrFloat(rt)) {
                        // int operands are widened to float first, as javac would
                        def bin = new BinaryExpression(toFloat(left, lt), e.operation,
                                toFloat(right, rt))
                        bin.sourcePosition = e
                        return castX(float_TYPE, bin)
                    }
                    e.leftExpression = left
                    e.rightExpression = right
                    return e
                }
                e.transformExpression(this)
            }
        }.visitMethod(method)
    }

    private static Expression floatLiteral(float f) {
        castX(float_TYPE, constX(Double.valueOf(f as double), true))
    }

    private static Expression toFloat(Expression e, ClassNode t) {
        isFloat(t) ? e : castX(float_TYPE, e)
    }

    private static Expression inlineConstant(VariableExpression v) {
        def f = v.accessedVariable
        if (f instanceof FieldNode && f.static && f.final && isPrimitiveType(f.type)
                && f.initialExpression instanceof ConstantExpression) {
            def value = ((ConstantExpression) f.initialExpression).value
            if (isFloat(f.type)) return floatLiteral(((Number) value).floatValue())
            def c = constX(value, true)
            c.type = f.type
            return c
        }
        null
    }

    private static boolean isFloat(ClassNode t) {
        t != null && (isPrimitiveFloat(t) || t == Float_TYPE)
    }

    private static boolean isIntOrFloat(ClassNode t) {
        t != null && (isFloat(t) || isPrimitiveInt(t) || isPrimitiveShort(t) || isPrimitiveByte(t) || t == Integer_TYPE)
    }

    // minimal local type inference, good enough for kernel-style code
    private static ClassNode typeOf(Expression e) {
        switch (e) {
            case CastExpression: return e.type
            case ConstantExpression: return e.type
            case VariableExpression:
                def av = ((VariableExpression) e).accessedVariable
                return av ? av.originType : null
            case PropertyExpression:
                def owner = typeOf(((PropertyExpression) e).objectExpression)
                def field = owner?.getField(((PropertyExpression) e).propertyAsString)
                return field?.type
            case MethodCallExpression:
                def mce = (MethodCallExpression) e
                def owner = typeOf(mce.objectExpression)
                if (owner == null) return null
                int argc = ((TupleExpression) mce.arguments).expressions.size()
                def returnTypes = owner.getMethods(mce.methodAsString)
                    .findAll { it.parameters.length == argc }*.returnType*.name.unique()
                return returnTypes.size() == 1 ? make(returnTypes[0]) : null
            default: return null
        }
    }
}
