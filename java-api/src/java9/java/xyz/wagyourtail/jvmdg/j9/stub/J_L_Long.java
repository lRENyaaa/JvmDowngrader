package xyz.wagyourtail.jvmdg.j9.stub;

import org.objectweb.asm.Opcodes;
import xyz.wagyourtail.jvmdg.Ref;
import xyz.wagyourtail.jvmdg.stub.Stub;

public class J_L_Long {

    @Stub(javaVersion = Opcodes.V9, ref = @Ref("java/lang/Long"))
    public static long parseLong(CharSequence s, int begin, int end, int radix) {
        return Long.parseLong(s.subSequence(begin, end).toString(), radix);
    }

    @Stub(javaVersion = Opcodes.V9, ref = @Ref("java/lang/Long"))
    public static long parseUnsignedLong(CharSequence s, int begin, int end, int radix) {
        return Long.parseUnsignedLong(s.subSequence(begin, end).toString(), radix);
    }
}