package xyz.wagyourtail.jvmdg.j12.stub;


import org.objectweb.asm.Opcodes;
import xyz.wagyourtail.jvmdg.stub.Stub;

import java.lang.reflect.Array;

public class J_L_Class {

    @Stub(javaVersion = Opcodes.V12)
    public static String descriptorString(Class<?> clazz) {
        if (clazz.isPrimitive()) {
            switch (clazz.getName()) {
                case "boolean":
                    return "Z";
                case "byte":
                    return "B";
                case "char":
                    return "C";
                case "short":
                    return "S";
                case "int":
                    return "I";
                case "long":
                    return "J";
                case "float":
                    return "F";
                case "double":
                    return "D";
                case "void":
                    return "V";
                default:
                    throw new InternalError("Unknown primitive type: " + clazz.getName());
            }
        }
        if (clazz.isArray()) {
            return clazz.getName().replace('.', '/');
        }
        return "L" + clazz.getName().replace('.', '/') + ";";
    }

    @Stub(javaVersion = Opcodes.V12)
    public static Class<?> componentType(Class<?> clazz) {
        return clazz.getComponentType();
    }

    @Stub(javaVersion = Opcodes.V12)
    public static Class<?> arrayType(Class<?> clazz) {
        return Array.newInstance(clazz, 0).getClass();
    }

}