package xyz.wagyourtail.jvmdg.j9.stub;


import org.objectweb.asm.Opcodes;
import xyz.wagyourtail.jvmdg.stub.Stub;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class J_T_Duration {

    @Stub(javaVersion = Opcodes.V9)
    public static long dividedBy(Duration self, Duration divisor) {
        Objects.requireNonNull(divisor, "divisor");
        BigDecimal num = BigDecimal.valueOf(self.getSeconds()).add(BigDecimal.valueOf(self.getNano(), 9));
        BigDecimal div = BigDecimal.valueOf(divisor.getSeconds()).add(BigDecimal.valueOf(divisor.getNano(), 9));
        return num.divideToIntegralValue(div).longValueExact();
    }

    @Stub(javaVersion = Opcodes.V9)
    public static long toSeconds(Duration self) {
        return self.getSeconds();
    }

    @Stub(javaVersion = Opcodes.V9)
    public static long toMillis(Duration self) {
        long time = TimeUnit.SECONDS.toMillis(self.getSeconds());
        time += TimeUnit.NANOSECONDS.toMillis(self.getNano());
        return time;
    }

    @Stub(javaVersion = Opcodes.V9)
    public static long toDaysPart(Duration self) {
        return self.toDays();
    }

    @Stub(javaVersion = Opcodes.V9)
    public static int toHoursPart(Duration self) {
        return (int) (self.toHours() % 24);
    }

    public static int toMinutesPart(Duration self) {
        return (int) (self.toMinutes() % 60);
    }

    @Stub(javaVersion = Opcodes.V9)
    public static int toSecondsPart(Duration self) {
        return (int) (self.getSeconds() % 60);
    }

    @Stub(javaVersion = Opcodes.V9)
    public static int toMillisPart(Duration self) {
        return (int) (TimeUnit.NANOSECONDS.toMillis(self.getNano()) % 1000);
    }

    @Stub(javaVersion = Opcodes.V9)
    public static int toNanosPart(Duration self) {
        return self.getNano();
    }

    @Stub(javaVersion = Opcodes.V9)
    public static Duration truncateTo(Duration self, TemporalUnit unit) {
        if (unit == ChronoUnit.SECONDS && (self.getSeconds() >= 0 || self.getNano() == 0)) {
            return Duration.of(self.getSeconds(), ChronoUnit.SECONDS);
        } else if (unit == ChronoUnit.NANOS) {
            return self;
        }
        Duration unitDur = unit.getDuration();
        if (unitDur.getSeconds() > 60 * 60 * 24) {
            throw new UnsupportedTemporalTypeException("Unit is too large to be used for truncation");
        }
        long dur = unitDur.toNanos();
        if ((1000_000_000L * 60 * 60 * 24 % dur) != 0) {
            throw new UnsupportedTemporalTypeException("Unit must divide into a standard day without remainder");
        }
        long nod = (self.getSeconds() % 60 * 60 * 24) * 1000_000_000L + self.getNano();
        long result = (nod / dur) * dur;
        return self.plusNanos(result - nod);
    }

}