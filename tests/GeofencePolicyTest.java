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
        check(!GeofencePolicy.outside(3100, 2000, 1000, 100));
        check(GeofencePolicy.outside(3101, 2000, 1000, 100));
        check(!GeofencePolicy.outside(Double.NaN, 1, 1, 1));
        check(!GeofencePolicy.outside(10000, 2000, 1000, Double.NaN));
        GeofencePolicy.Window w = new GeofencePolicy.Window();
        w.update(AWAY, false, 1000);
        check(w.remaining(1000, 60000) == 60000);
        check(!w.claim(15999, 15000, 60000));
        check(w.claim(16000, 15000, 60000));
        check(!w.claim(16000, 15000, 60000));
        w.update(UNKNOWN, true, 20000);
        check(w.remaining(21000, 60000) == 40000);
        check(w.claim(47000, 15000, 60000));
        check(w.checks() == 3);
        check(w.claim(61000, 15000, 60000));
        check(w.checks() == 4);
        check(!w.claim(62000, 15000, 60000));
        check(w.remaining(62000, 60000) == 0);
        w.markOutside(); check(w.outside());
        GeofencePolicy.Window jitter = new GeofencePolicy.Window();
        jitter.update(AWAY, false, 0);
        check(jitter.claim(60050, 15000, 60000) && jitter.checks() == 4);
        check(!jitter.claim(60051, 15000, 60000));
        w.update(HOME, false, 63000);
        check(!w.active() && !w.outside());
        w.update(AWAY, false, 64000);
        check(w.nextIn(64000, 15000) == 15000);
        w.update(UNKNOWN, false, 65000);
        check(!w.active());
        w.update(AWAY, false, 66000);
        check(!w.claim(140000, 15000, 60000));
        check(w.remaining(130000, 60000) == 0);
        System.out.println("GeofencePolicy: " + checks + " checks passed");
    }
}
