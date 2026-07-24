package com.pulsaride.dispatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pulsaride.seed-data")
public class SeedDataProperties {
    private boolean enabled = true;
    private String simulatorDataDir = "../simulator/data";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSimulatorDataDir() { return simulatorDataDir; }
    public void setSimulatorDataDir(String simulatorDataDir) { this.simulatorDataDir = simulatorDataDir; }
}
