package com.nas.dashboard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

public class MainActivity extends Activity {
    private int selectedTab;
    private OverviewController overview;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        overview = new OverviewController(this);
        findViewById(R.id.tab_overview).setOnClickListener(v -> selectTab(0));
        findViewById(R.id.tab_services).setOnClickListener(v -> selectTab(1));
        findViewById(R.id.tab_notifications).setOnClickListener(v -> selectTab(2));
        findViewById(R.id.tab_settings).setOnClickListener(v -> selectTab(3));
        findViewById(R.id.network_settings_button).setOnClickListener(v ->
            startActivity(new Intent(this, NetworkSettingsActivity.class)));
        findViewById(R.id.presence_settings_button).setOnClickListener(v ->
            startActivity(new Intent(this, PresenceSettingsActivity.class)));
        findViewById(R.id.scan_setup_button).setOnClickListener(v ->
            startActivity(new Intent(this, SetupScannerActivity.class)));
        bindModule(R.id.module_mihomo, "mihomo");
        findViewById(R.id.module_articles).setOnClickListener(v -> startActivity(new Intent(this, ArticlesActivity.class)));
        findViewById(R.id.module_sms).setOnClickListener(v -> startActivity(new Intent(this, SmsActivity.class)));
        bindModule(R.id.module_tasks, "tasks");
        findViewById(R.id.module_rhino).setOnClickListener(v -> startActivity(new Intent(this, RhinoActivity.class)));
        bindModule(R.id.module_certificates, "certificates");
        bindModule(R.id.module_aliyun, "aliyun");
        bindModule(R.id.module_zerotier, "zerotier");
        selectTab(savedInstanceState == null ? 0 : savedInstanceState.getInt("selected_tab", 0));
    }


    private void bindModule(int id, String key) {
        findViewById(id).setOnClickListener(v -> startActivity(new Intent(this, ServiceModuleActivity.class).putExtra("module", key)));
    }

    private void selectTab(int tab) {
        selectedTab = tab;
        int[] ids = {R.id.tab_overview, R.id.tab_services, R.id.tab_notifications, R.id.tab_settings};
        int[] titles = {R.string.tab_overview, R.string.tab_services, R.string.tab_notifications, R.string.tab_settings};
        ((TextView) findViewById(R.id.page_title)).setText(titles[tab]);
        findViewById(R.id.overview_panel).setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        if (tab == 0) overview.show(); else overview.hide();
        findViewById(R.id.services_panel).setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        findViewById(R.id.settings_panel).setVisibility(tab == 3 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < ids.length; i++) {
            TextView item = findViewById(ids[i]);
            item.setSelected(i == tab);
            int color = Color.parseColor(i == tab ? "#4D669D" : "#706B75");
            item.setTextColor(color);
            for (android.graphics.drawable.Drawable icon : item.getCompoundDrawables()) {
                if (icon != null) icon.mutate().setTint(color);
            }
            item.setBackgroundResource(i == tab ? R.drawable.tab_selected : R.drawable.tab_unselected);
        }
    }

    @Override protected void onStart() { super.onStart(); if (selectedTab == 0) overview.show(); }
    @Override protected void onResume() { super.onResume(); if (selectedTab == 0) overview.refreshReadMarkers(); }
    @Override protected void onStop() { overview.hide(); super.onStop(); }
    @Override protected void onDestroy() { overview.close(); super.onDestroy(); }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("selected_tab", selectedTab);
        super.onSaveInstanceState(state);
    }
}
