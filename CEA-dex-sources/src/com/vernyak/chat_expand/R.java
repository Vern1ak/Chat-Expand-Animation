package com.vernyak.chat_expand;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Reflection helpers for Telegram/exteraGram internals. Telegram classes are never linked at compile
 * time (names of private fields and overloads shift between builds), everything goes through here.
 * Equivalent of the old Python _field/_set_bool and of attribute/method access through Chaquopy.
 */
final class R {
    private R() {}

    private static final Object MISSING = new Object();
    private static final HashMap<String, Object> FIELDS = new HashMap<String, Object>();
    private static final HashMap<String, List<Method>> METHODS = new HashMap<String, List<Method>>();
    private static final HashMap<String, Object> CLASSES = new HashMap<String, Object>();

    static synchronized Class<?> cls(String name) {
        Object c = CLASSES.get(name);
        if (c == null) {
            try {
                ClassLoader l = CEA.appLoader();
                c = Class.forName(name, false, l != null ? l : R.class.getClassLoader());
            } catch (Throwable t) {
                c = MISSING;
            }
            CLASSES.put(name, c);
        }
        return c == MISSING ? null : (Class<?>) c;
    }

    static boolean isInstance(Object o, String className) {
        if (o == null) return false;
        Class<?> c = cls(className);
        return c != null && c.isInstance(o);
    }

    // ------------------------------------------------------------------ fields

    private static synchronized Field findField(Class<?> start, String name) {
        String key = start.getName() + "#" + name;
        Object cached = FIELDS.get(key);
        if (cached != null) return cached == MISSING ? null : (Field) cached;
        Class<?> c = start;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                FIELDS.put(key, f);
                return f;
            } catch (Throwable t) {
                c = c.getSuperclass();
            }
        }
        FIELDS.put(key, MISSING);
        return null;
    }

    /** Field value (boxed) or null when the field does not exist / cannot be read. */
    static Object get(Object obj, String name) {
        if (obj == null) return null;
        try {
            Field f = findField(obj.getClass(), name);
            return f != null ? f.get(obj) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static Object getStatic(String className, String name) {
        try {
            Class<?> c = cls(className);
            if (c == null) return null;
            Field f = findField(c, name);
            return f != null ? f.get(null) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean hasField(Object obj, String name) {
        return obj != null && findField(obj.getClass(), name) != null;
    }

    static boolean set(Object obj, String name, Object value) {
        if (obj == null) return false;
        try {
            Field f = findField(obj.getClass(), name);
            if (f == null) return false;
            f.set(obj, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean setLong(Object obj, String name, long value) {
        try {
            Field f = findField(obj.getClass(), name);
            if (f == null) return false;
            f.setLong(obj, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static double num(Object o, double def) {
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }

    static int intv(Object o, int def) {
        return o instanceof Number ? ((Number) o).intValue() : def;
    }

    static boolean bool(Object o, boolean def) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).doubleValue() != 0.0;
        return def;
    }

    // ----------------------------------------------------------------- methods

    private static Class<?> wrap(Class<?> p) {
        if (!p.isPrimitive()) return p;
        if (p == boolean.class) return Boolean.class;
        if (p == int.class) return Integer.class;
        if (p == long.class) return Long.class;
        if (p == float.class) return Float.class;
        if (p == double.class) return Double.class;
        if (p == short.class) return Short.class;
        if (p == byte.class) return Byte.class;
        if (p == char.class) return Character.class;
        return p;
    }

    private static boolean accepts(Method m, Object[] args) {
        Class<?>[] pt = m.getParameterTypes();
        if (pt.length != args.length) return false;
        for (int i = 0; i < pt.length; i++) {
            Object a = args[i];
            if (a == null) {
                if (pt[i].isPrimitive()) return false;
            } else {
                Class<?> w = wrap(pt[i]);
                if (!w.isInstance(a)) {
                    // numeric widening (int -> long/float/double, float -> double)
                    if (a instanceof Number && Number.class.isAssignableFrom(w) && w != Integer.class) continue;
                    return false;
                }
            }
        }
        return true;
    }

    private static Object convert(Class<?> p, Object a) {
        if (a instanceof Number) {
            Class<?> w = wrap(p);
            Number n = (Number) a;
            if (w == Long.class) return n.longValue();
            if (w == Float.class) return n.floatValue();
            if (w == Double.class) return n.doubleValue();
            if (w == Integer.class) return n.intValue();
        }
        return a;
    }

    private static synchronized List<Method> candidates(Class<?> start, String name, int argc) {
        String key = start.getName() + "#" + name + "/" + argc;
        List<Method> list = METHODS.get(key);
        if (list != null) return list;
        list = new ArrayList<Method>();
        Class<?> c = start;
        while (c != null) {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().length == argc && !m.isBridge()) {
                        try {
                            m.setAccessible(true);
                        } catch (Throwable ignored) {
                        }
                        list.add(m);
                    }
                }
            } catch (Throwable ignored) {
            }
            c = c.getSuperclass();
        }
        // interface default methods / methods only declared on interfaces
        if (list.isEmpty()) {
            try {
                for (Method m : start.getMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().length == argc) list.add(m);
                }
            } catch (Throwable ignored) {
            }
        }
        METHODS.put(key, list);
        return list;
    }

    static Method find(Class<?> c, String name, int argc) {
        List<Method> l = candidates(c, name, argc);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Invoke an instance method resolved by name + argument count/types; throws on failure. */
    static Object call(Object target, String name, Object... args) throws Exception {
        if (target == null) throw new NullPointerException("call " + name + " on null");
        List<Method> l = candidates(target.getClass(), name, args.length);
        Method best = null;
        for (Method m : l) {
            if (accepts(m, args)) {
                best = m;
                break;
            }
        }
        if (best == null && !l.isEmpty()) best = l.get(0);
        if (best == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name + "/" + args.length);
        Class<?>[] pt = best.getParameterTypes();
        Object[] conv = new Object[args.length];
        for (int i = 0; i < args.length; i++) conv[i] = convert(pt[i], args[i]);
        try {
            return best.invoke(target, conv);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw e;
        }
    }

    /** Like call(), but returns null instead of throwing. */
    static Object tryCall(Object target, String name, Object... args) {
        try {
            return call(target, name, args);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object callStatic(Class<?> c, String name, Object... args) throws Exception {
        List<Method> l = candidates(c, name, args.length);
        Method best = null;
        for (Method m : l) {
            if (accepts(m, args)) {
                best = m;
                break;
            }
        }
        if (best == null && !l.isEmpty()) best = l.get(0);
        if (best == null) throw new NoSuchMethodException(c.getName() + "." + name + "/" + args.length);
        Class<?>[] pt = best.getParameterTypes();
        Object[] conv = new Object[args.length];
        for (int i = 0; i < args.length; i++) conv[i] = convert(pt[i], args[i]);
        try {
            return best.invoke(null, conv);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }

    static double d(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
    }
}
