import com.nas.dashboard.network.OverviewPolicy;

public class OverviewPolicyTest {
    private static int checks;
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("Check " + checks); }
    public static void main(String[] args) {
        long now = OverviewPolicy.parseTimestamp("2026-10-14T12:00:00+08:00");
        check(OverviewPolicy.isTodayUnread("2026-10-14T01:00:00+08:00", false, "video", now));
        check(OverviewPolicy.isTodayUnread("2026-10-13T16:01:00Z", false, "video", now));
        check(!OverviewPolicy.isTodayUnread("2026-10-13T15:59:00Z", false, "video", now));
        check(!OverviewPolicy.isTodayUnread("2026-10-14T01:00:00+08:00", true, "video", now));
        check(!OverviewPolicy.isTodayUnread("2026-10-14T01:00:00+08:00", false, "", now));
        check(!OverviewPolicy.isTodayUnread("invalid", false, "video", now));
        check(OverviewPolicy.parseTimestamp("2026-10-14T12:00:00.123456+08:00") == now + 123);
        check(OverviewPolicy.parseTimestamp("2026-02-30") == null);
        check(OverviewPolicy.GREEN.equals(OverviewPolicy.certificateColor(15.01)));
        check(OverviewPolicy.YELLOW.equals(OverviewPolicy.certificateColor(15.0)));
        check(OverviewPolicy.YELLOW.equals(OverviewPolicy.certificateColor(14.99)));
        check(OverviewPolicy.YELLOW.equals(OverviewPolicy.certificateColor(-1.0)));
        check(OverviewPolicy.UNKNOWN.equals(OverviewPolicy.certificateColor(null)));
        check(OverviewPolicy.UNKNOWN.equals(OverviewPolicy.certificateColor(Double.NaN)));
        check(OverviewPolicy.daysRemaining("2026-10-29T12:00:00+08:00", now) == 15.0);
        System.out.println("PASS: " + checks + " overview date/read/certificate checks");
    }
}
