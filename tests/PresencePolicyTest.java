import com.nas.dashboard.presence.PresencePolicy;
import static com.nas.dashboard.presence.PresencePolicy.State.*;

public final class PresencePolicyTest {
    public static void main(String[] args) {
        int checks = 0;
        for (boolean configured : new boolean[]{false, true})
            for (boolean connected : new boolean[]{false, true})
                for (boolean readable : new boolean[]{false, true})
                    for (boolean matches : new boolean[]{false, true}) {
                        Object expected = !configured ? UNKNOWN : !connected ? AWAY : !readable ? UNKNOWN : matches ? HOME : AWAY;
                        if (PresencePolicy.detectWifi(configured, connected, readable, matches) != expected)
                            throw new AssertionError("Incorrect home Wi-Fi classification");
                        checks++;
                    }
        System.out.println("PresencePolicy: " + checks + " checks passed");
    }
}
