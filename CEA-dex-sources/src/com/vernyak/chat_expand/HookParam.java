package com.vernyak.chat_expand;

import java.lang.reflect.Method;

/** Reflective view of an Xposed-style MethodHookParam handed over by the Python hook shim. */
final class HookParam {
    final Object raw;
    final Object[] args;
    final Object thisObject;
    final Object method;

    HookParam(Object raw) {
        this.raw = raw;
        Object a = R.get(raw, "args");
        this.args = a instanceof Object[] ? (Object[]) a : new Object[0];
        this.thisObject = R.get(raw, "thisObject");
        this.method = R.get(raw, "method");
    }

    Object getResult() {
        try {
            return R.call(raw, "getResult");
        } catch (Throwable t) {
            return null;
        }
    }

    void setResult(Object v) {
        try {
            R.call(raw, "setResult", v);
        } catch (Throwable t) {
            throw new RuntimeException("setResult failed: " + t, t);
        }
    }

    String returnTypeName() {
        try {
            if (method instanceof Method) return ((Method) method).getReturnType().getName();
        } catch (Throwable ignored) {
        }
        return "void";
    }
}
