import com.nas.dashboard.presence.PresencePolicy;
import static com.nas.dashboard.presence.PresencePolicy.State.*;

public final class PresencePolicyTest {
    private static int timerChecks;
    private static void remaining(long expected, long actual) {
        timerChecks++;
        if (expected != actual) throw new AssertionError("Remaining " + actual + ", expected " + expected);
    }
    public static void main(String[] args) {
        int routeChecks = 0;
        for (boolean configured : new boolean[]{false, true}) {
            for (boolean selecting : new boolean[]{false, true}) {
                for (boolean local : new boolean[]{false, true}) {
                    for (boolean online : new boolean[]{false, true}) {
                        Object expected = !configured || selecting || !online ? UNKNOWN : local ? HOME : AWAY;
                        Object actual = PresencePolicy.detectRoute(configured, selecting, local, online);
                        if (expected != actual) throw new AssertionError(expected + " != " + actual);
                        routeChecks++;
                    }
                }
            }
        }
        PresencePolicy.AwayTimer timer = new PresencePolicy.AwayTimer();
        long test = PresencePolicy.TEST_AWAY_DELAY_MS;
        remaining(60_000, test);
        remaining(14_400_000, PresencePolicy.NORMAL_AWAY_DELAY_MS);
        remaining(60_000, timer.remaining(AWAY, 0, test));
        remaining(40_000, timer.remaining(AWAY, 20_000, test)); // Previous 20-second threshold no longer sends away.
        remaining(1, timer.remaining(AWAY, 59_999, test));
        remaining(0, timer.remaining(AWAY, 60_000, test));
        remaining(0, timer.remaining(AWAY, 70_000, test));
        remaining(test, timer.remaining(HOME, 71_000, test));
        remaining(test, timer.remaining(AWAY, 72_000, test)); // Reconnection cancels earlier time.
        remaining(test, timer.remaining(UNKNOWN, 80_000, test));
        remaining(test, timer.remaining(AWAY, 90_000, test)); // Cannot count unknown time as continuous away.
        remaining(1, timer.remaining(AWAY, 149_999, test));
        remaining(0, timer.remaining(AWAY, 150_000, test));
        long normal = PresencePolicy.NORMAL_AWAY_DELAY_MS;
        timer = new PresencePolicy.AwayTimer();
        remaining(normal, timer.remaining(AWAY, 100, normal));
        remaining(normal - test, timer.remaining(AWAY, 100 + test, normal));
        remaining(1, timer.remaining(AWAY, 100 + normal - 1, normal));
        remaining(0, timer.remaining(AWAY, 100 + normal, normal));
        remaining(0, timer.remaining(AWAY, 100 + normal, test)); // Changing mode uses elapsed time.
        timer = new PresencePolicy.AwayTimer();
        remaining(test, timer.remaining(AWAY, 0, test));
        remaining(30_000, timer.remaining(UNKNOWN, true, 30_000, test));
        remaining(1, timer.remaining(UNKNOWN, true, 59_999, test));
        remaining(0, timer.remaining(AWAY, 60_000, test)); // Route refresh does not restart external time.
        remaining(test, timer.remaining(HOME, 61_000, test));
        remaining(test, timer.remaining(AWAY, 70_000, test));
        remaining(test, timer.remaining(UNKNOWN, false, 80_000, test)); // Offline cancels continuous external time.
        remaining(test, timer.remaining(AWAY, 90_000, test));
        long[] values = {60_000, 59_001, 59_000, 1, 0, -1000, 14_400_000};
        String[] labels = {"00:01:00", "00:01:00", "00:00:59", "00:00:01", "00:00:00", "00:00:00", "04:00:00"};
        for (int i = 0; i < values.length; i++) {
            if (!labels[i].equals(PresencePolicy.formatRemaining(values[i]))) throw new AssertionError("Countdown " + values[i]);
        }
        System.out.println("Countdown: " + values.length + " format checks passed");
        System.out.println("AwayTimer: " + timerChecks + " checks passed");
        System.out.println("PresencePolicy: " + routeChecks + " checks passed");
    }
}
