import com.nas.dashboard.presence.*;
import static com.nas.dashboard.presence.PresencePolicy.State.*;
public final class GeofencePolicyTest {
    private static int checks;
    private static void check(boolean value) { if (!value) throw new AssertionError("Check " + checks); checks++; }
    public static void main(String[] args) {
        check(GeofencePolicy.distance(31.6, 104.4, 31.6, 104.4) == 0);
        check(Math.abs(GeofencePolicy.distance(0, 0, 0, 1) - 111195) < 2);
        check(GeofencePolicy.distance(0, 179.99, 0, -179.99) < 2300);
        check(Double.isFinite(GeofencePolicy.distance(90, 0, -90, 180)));
        check(!GeofencePolicy.outside(2100, 2000, 100));
        check(GeofencePolicy.outside(2101, 2000, 100));
        check(!GeofencePolicy.outside(Double.NaN, 1, 1));
        check(!GeofencePolicy.outside(10000, 2000, Double.NaN));
        long normal = GeofencePolicy.interval(false), test = GeofencePolicy.interval(true);
        check(normal == 1800000 && test == 60000);
        GeofencePolicy.Schedule w = new GeofencePolicy.Schedule();
        w.update(AWAY, 1000);
        check(!w.claim(1000 + normal - 1, normal));
        check(w.claim(1000 + normal, normal));
        check(!w.claim(1000 + normal, normal));
        // No four-hour fallback: checks continue for days until a fix confirms departure.
        check(w.claim(1000 + 48 * normal, normal));
        check(w.checks() == 2 && !w.outside());
        check(w.nextIn(1000 + 48 * normal, normal) == normal);
        w.markOutside();
        check(!w.claim(1000 + 100 * normal, normal));
        w.reschedule(1000 + 100 * normal);
        check(!w.claim(1000 + 101 * normal, normal));
        // A manual check does not resume automatic positioning after departure.
        w.checked(1000 + 102 * normal);
        check(w.outside() && !w.claim(1000 + 103 * normal, normal));
        w.update(HOME, 200000000);
        check(!w.active() && !w.outside());
        w.update(AWAY, 200001000);
        check(w.nextIn(200001000, normal) == normal);
        // Switching mode starts a fresh interval, both to test and back to normal.
        w.reschedule(200002000);
        check(!w.claim(200002000 + test - 1, test));
        check(w.claim(200002000 + test, test));
        w.reschedule(200100000);
        check(!w.claim(200100000 + test, normal));
        check(w.claim(200100000 + normal, normal));
        w.update(UNKNOWN, 202000000);
        check(w.active());
        w.update(AWAY, 204000000);
        check(w.claim(204000000, normal));
        check(!w.claim(204000001, normal));
        check(w.nextIn(204000001, normal) == normal - 1);
        System.out.println("GeofencePolicy: " + checks + " checks passed");
    }
}
