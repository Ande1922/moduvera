package io.github.ande1922.moduvera.migration.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("moduvera.database.migration")
public class ModuveraDatabaseMigrationProperties {

    private ModuveraDatabaseMigrationMode mode = ModuveraDatabaseMigrationMode.DISABLED;
    private boolean initialize;

    public ModuveraDatabaseMigrationMode getMode() {
        return mode;
    }

    public void setMode(ModuveraDatabaseMigrationMode mode) {
        this.mode = mode;
    }

    public boolean isInitialize() {
        return initialize;
    }

    public void setInitialize(boolean initialize) {
        this.initialize = initialize;
    }
}
