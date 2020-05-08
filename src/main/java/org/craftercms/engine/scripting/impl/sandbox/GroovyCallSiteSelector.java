/*
 * Copyright (C) 2007-2020 Crafter Software Corporation. All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as published by
 * the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.craftercms.engine.scripting.impl.sandbox;

import com.google.common.primitives.Primitives;
import groovy.lang.GString;
import groovy.lang.GroovyInterceptable;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.apache.commons.lang3.ClassUtils;
import org.codehaus.groovy.runtime.typehandling.DefaultTypeTransformation;

/**
 * Copied from https://github.com/jenkinsci/script-security-plugin/blob/master/src/main/java/org/jenkinsci/plugins/scriptsecurity/sandbox/groovy/GroovyCallSiteSelector.java
 * Can't be reused because the class is not public
 *
 * Assists in determination of which method or other JVM element is actually about to be called by Groovy.
 * Most of this just duplicates what {@link java.lang.invoke.MethodHandles.Lookup} and {@link java.lang.invoke.MethodHandle#asType} do,
 * but {@link org.codehaus.groovy.vmplugin.v7.TypeTransformers} shows that there are Groovy-specific complications.
 * Comments in https://github.com/kohsuke/groovy-sandbox/issues/7 note that it would be great for the sandbox itself to just tell us what the call site is so we would not have to guess.
 */
class GroovyCallSiteSelector {

    private static boolean matches(@Nonnull Class<?>[] parameterTypes, @Nonnull Object[] parameters, boolean varargs) {
        if (varargs) {
            parameters = parametersForVarargs(parameterTypes, parameters);
        }
        if (parameters.length != parameterTypes.length) {
            return false;
        }
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameters[i] == null) {
                if (parameterTypes[i].isPrimitive()) {
                    return false;
                } else {
                    // A null argument is assignable to any reference-typed parameter.
                    continue;
                }
            }
            if (parameterTypes[i].isInstance(parameters[i])) {
                // OK, this parameter matches.
                continue;
            }
            if (
                    parameterTypes[i].isPrimitive()
                    && parameters[i] != null
                    && isInstancePrimitive(ClassUtils.primitiveToWrapper(parameterTypes[i]), parameters[i])
            ) {
                // Groovy passes primitive values as objects (for example, passes 0 as Integer(0))
                // The prior test fails as int.class.isInstance(new Integer(0)) returns false.
                continue;
            }
            // TODO what about a primitive parameter type and a wrapped parameter?
            if (parameterTypes[i] == String.class && parameters[i] instanceof GString) {
                // Cf. SandboxInterceptorTest and class Javadoc.
                continue;
            }
            // Mismatch.
            return false;
        }
        return true;
    }

    /**
     * Translates a method parameter list with varargs possibly spliced into the end into the actual parameters to be passed to the JVM call.
     */
    private static Object[] parametersForVarargs(Class<?>[] parameterTypes, Object[] parameters) {
        int fixedLen = parameterTypes.length - 1;
        Class<?> componentType = parameterTypes[fixedLen].getComponentType();
        assert componentType != null;
        if (componentType.isPrimitive()) {
            componentType = ClassUtils.primitiveToWrapper(componentType);
        }
        int arrayLength = parameters.length - fixedLen;

        if (arrayLength >= 0) {
            if (arrayLength == 1 && parameterTypes[fixedLen].isInstance(parameters[fixedLen])) {
                // not a varargs call
                return parameters;
            } else if ((arrayLength > 0 && (componentType.isInstance(parameters[fixedLen]) || parameters[fixedLen] == null)) ||
                    arrayLength == 0) {
                Object array = DefaultTypeTransformation.castToVargsArray(parameters, fixedLen, parameterTypes[fixedLen]);
                Object[] parameters2 = new Object[fixedLen + 1];
                System.arraycopy(parameters, 0, parameters2, 0, fixedLen);
                parameters2[fixedLen] = array;

                return parameters2;
            }
        }
        return parameters;
    }

    /**
     * {@link Class#isInstance} extended to handle some important cases of primitive types.
     */
    private static boolean isInstancePrimitive(@Nonnull Class<?> type, @Nonnull Object instance) {
        if (type.isInstance(instance)) {
            return true;
        }
        // https://docs.oracle.com/javase/specs/jls/se8/html/jls-5.html#jls-5.1.2
        if (instance instanceof Number) {
            if (type == Long.class && instance instanceof Integer) {
                return true; // widening
            }
            if (type == Integer.class && instance instanceof Long) {
                Long n = (Long) instance;
                if (n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) {
                    return true; // safe narrowing
                }
            }
            // TODO etc. for other conversions if they ever come up
        }
        return false;
    }

    /**
     * Looks up the most general possible definition of a given method call.
     * Preferentially searches for compatible definitions in supertypes.
     * @param receiver an actual receiver object
     * @param method the method name
     * @param args a set of actual arguments
     */
    public static @CheckForNull Method method(@Nonnull Object receiver, @Nonnull String method, @Nonnull Object[] args) {
        Set<Class<?>> types = types(receiver);
        if (types.contains(GroovyInterceptable.class) && !"invokeMethod".equals(method)) {
            return method(receiver, "invokeMethod", new Object[]{ method, args });
        }
        for (Class<?> c : types) {
            Method candidate = findMatchingMethod(c, method, args);
            if (candidate != null) {
                return candidate;
            }
        }
        if (receiver instanceof GString) { // cf. GString.invokeMethod
            Method candidate = findMatchingMethod(String.class, method, args);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    public static @CheckForNull Constructor<?> constructor(@Nonnull Class<?> receiver, @Nonnull Object[] args) {
        Constructor<?>[] constructors = receiver.getDeclaredConstructors();
        Constructor<?> candidate = null;
        for (Constructor<?> c : constructors) {
            if (matches(c.getParameterTypes(), args, c.isVarArgs())) {
                if (candidate == null || isMoreSpecific(c, c.getParameterTypes(), c.isVarArgs(), candidate, candidate.getParameterTypes(), candidate.isVarArgs())) {
                    candidate = c;
                }
            }
        }
        if (candidate != null) {
            return candidate;
        }

        // Only check for the magic Map constructor if we haven't already found a real constructor.
        // Also note that this logic is derived from how Groovy itself decides to use the magic Map constructor, at
        // MetaClassImpl#invokeConstructor(Class, Object[]).
        if (args.length == 1 && args[0] instanceof Map) {
            for (Constructor<?> c : constructors) {
                if (c.getParameterTypes().length == 0 && !c.isVarArgs()) {
                    return c;
                }
            }
        }

        return null;
    }

    public static @CheckForNull Method staticMethod(@Nonnull Class<?> receiver, @Nonnull String method, @Nonnull Object[] args) {
        return findMatchingMethod(receiver, method, args);
    }

    private static Method findMatchingMethod(@Nonnull Class<?> receiver, @Nonnull String method, @Nonnull Object[] args) {
        Method candidate = null;

        for (Method m : receiver.getDeclaredMethods()) {
            if (m != null) {
                boolean isVarArgs = isVarArgsMethod(m, args);
                if (m.getName().equals(method) && (matches(m.getParameterTypes(), args, isVarArgs))) {
                    if (candidate == null || isMoreSpecific(m, m.getParameterTypes(), isVarArgs, candidate,
                            candidate.getParameterTypes(), isVarArgsMethod(candidate, args))) {
                        candidate = m;
                    }
                }
            }
        }
        return candidate;
    }

    /**
     * Emulates, with some tweaks, {@link org.codehaus.groovy.reflection.ParameterTypes#isVargsMethod(Object[])}
     */
    private static boolean isVarArgsMethod(@Nonnull Method m, @Nonnull Object[] args) {
        if (m.isVarArgs()) {
            return true;
        }
        Class<?>[] paramTypes = m.getParameterTypes();

        // If there's 0 or only 1 parameter type, we don't want to do varargs magic. Normal callsite selector logic works then.
        if (paramTypes.length < 2) {
            return false;
        }

        int lastIndex = paramTypes.length - 1;
        // If there are more arguments than parameter types and the last parameter type is an array, we may be vargy.
        if (paramTypes[lastIndex].isArray() && args.length > paramTypes.length) {
            Class<?> lastClass = paramTypes[lastIndex].getComponentType();
            // Check each possible vararg to see if it can be cast to the array's component type or is null. If not, we're not vargy.
            for (int i = lastIndex; i < args.length; i++) {
                if (args[i] != null && !lastClass.isAssignableFrom(args[i].getClass())) {
                    return false;
                }
            }
            // Otherwise, we are.
            return true;
        }

        // Fallback to we're not vargy.
        return false;
    }

    public static @CheckForNull Field field(@Nonnull Object receiver, @Nonnull String field) {
        for (Class<?> c : types(receiver)) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(field)) {
                    return f;
                }
            }
        }
        return null;
    }

    public static @CheckForNull Field staticField(@Nonnull Class<?> receiver, @Nonnull String field) {
        for (Field f : receiver.getDeclaredFields()) {
            if (f.getName().equals(field)) {
                return f;
            }
        }
        return null;
    }

    private static Set<Class<?>> types(@Nonnull Object o) {
        Set<Class<?>> types = new LinkedHashSet<Class<?>>();
        visitTypes(types, o.getClass());
        return types;
    }
    private static void visitTypes(@Nonnull Set<Class<?>> types, @Nonnull Class<?> c) {
        Class<?> s = c.getSuperclass();
        if (s != null) {
            visitTypes(types, s);
        }
        for (Class<?> i : c.getInterfaces()) {
            visitTypes(types, i);
        }
        // Visit supertypes first.
        types.add(c);
    }

    // TODO nowhere close to implementing http://docs.oracle.com/javase/specs/jls/se8/html/jls-15.html#jls-15.12.2.5
    private static boolean isMoreSpecific(AccessibleObject more, Class<?>[] moreParams, boolean moreVarArgs, AccessibleObject less, Class<?>[] lessParams, boolean lessVarArgs) { // TODO clumsy arguments pending Executable in Java 8
        if (lessVarArgs && !moreVarArgs) {
            return true; // main() vs. main(String...) on []
        } else if (!lessVarArgs && moreVarArgs) {
            return false;
        }
        // TODO what about passing [arg] to log(String...) vs. log(String, String...)?
        if (moreParams.length != lessParams.length) {
            throw new IllegalStateException("cannot compare " + more + " to " + less);
        }
        for (int i = 0; i < moreParams.length; i++) {
            Class<?> moreParam = Primitives.wrap(moreParams[i]);
            Class<?> lessParam = Primitives.wrap(lessParams[i]);
            if (moreParam.isAssignableFrom(lessParam)) {
                return false;
            } else if (lessParam.isAssignableFrom(moreParam)) {
                return true;
            }
            if (moreParam == Long.class && lessParam == Integer.class) {
                return false;
            } else if (moreParam == Integer.class && lessParam == Long.class) {
                return true;
            }
        }
        // Incomparable. Arbitrarily pick one of them.
        return more.toString().compareTo(less.toString()) > 0;
    }

    private GroovyCallSiteSelector() {}

}