package com.nas.dashboard;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SmsNotificationPolicyTest {
    @Test public void originalTimestampControlsOtpExpiry() throws Exception {
        long now = java.time.Instant.parse("2026-11-22T12:00:00Z").toEpochMilli();
        JSONObject row = new JSONObject().put("sent_at", "2026-11-22T11:57:01Z")
            .put("received_at", "2026-11-22T12:00:00Z");
        assertTrue(SmsNotificationState.recent(row, now, 180000));
        assertFalse(SmsNotificationState.recent(row, now + 1000, 180000));
        row.put("sent_at", "2026-11-22T12:00:01Z");
        assertTrue(SmsNotificationState.recent(row, now, 180000));
        row.put("sent_at", "2026-11-22T12:00:31Z");
        assertFalse(SmsNotificationState.recent(row, now, 180000));
        row.put("sent_at", "invalid");
        assertFalse(SmsNotificationState.recent(row, now, 180000));
    }
    @Test public void codesKeepLeadingZerosAndRejectUnexpectedContent() {
        assertTrue(SmsNotificationState.validCode("001234"));
        assertFalse(SmsNotificationState.validCode("123"));
        assertFalse(SmsNotificationState.validCode("1234\n"));
        assertFalse(SmsNotificationState.validCode("12 3456"));
    }
    @Test public void quickFillRecognizesOtpAndRejectsPasswordFields() {
        assertTrue(SmsQuickFillService.otpLabel("请输入验证码"));
        assertTrue(SmsQuickFillService.otpLabel("app:id/otp"));
        assertTrue(SmsQuickFillService.otpLabel("app:id/sms_code"));
        assertFalse(SmsQuickFillService.otpLabel("password"));
        assertFalse(SmsQuickFillService.otpLabel("credit_card_verification_code"));
        assertFalse(SmsQuickFillService.otpLabel("普通输入框"));
    }
}
