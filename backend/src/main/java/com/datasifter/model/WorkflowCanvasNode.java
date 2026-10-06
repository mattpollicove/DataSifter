package com.datasifter.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class WorkflowCanvasNode {
    private String id;
    private String type;
    private String name;
    private String config;
    private int x;
    private int y;
    private Map<String, String> settings = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }
    public int getX() { return x; }
    public void setX(int x) { this.x = x; }
    public int getY() { return y; }
    public void setY(int y) { this.y = y; }
    public Map<String, String> getSettings() { return settings; }
    public void setSettings(Map<String, String> settings) {
        this.settings = settings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(settings);
    }
}
