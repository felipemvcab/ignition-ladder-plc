package com.felipe.ladder.gateway;

import com.felipe.ladder.engine.CyclicScanEngine;
import com.felipe.ladder.engine.TagImageManager;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.tags.managed.ManagedTagProvider;
import com.inductiveautomation.ignition.gateway.tags.managed.ManagedTagProviderConfiguration;
import com.inductiveautomation.ignition.gateway.web.systemjs.SystemJsModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Optional;

/**
 * Ignition 8.3 Gateway Module Hook for Ladder Logic Studio by Felipe Integrations.
 */
public class LadderGatewayHook extends AbstractGatewayModuleHook {

    private static final Logger logger = LoggerFactory.getLogger(LadderGatewayHook.class);
    public static final String SERVLET_MOUNT = "ladder";

    private static volatile LadderGatewayHook instance;

    private GatewayContext context;
    private ManagedTagProvider managedTagProvider;
    private TagImageManager tagImageManager;
    private CyclicScanEngine scanEngine;

    public LadderGatewayHook() {
        instance = this;
    }

    public static LadderGatewayHook getInstance() {
        return instance;
    }

    @Override
    public void setup(GatewayContext gatewayContext) {
        this.context = gatewayContext;
        instance = this;
        logger.info("Setting up Ladder Logic Studio Module (Felipe Integrations)...");

        try {
            SystemJsModule jsModule = new SystemJsModule("LadderLogicStudio", "/res/ladderplc/LadderLogicStudio.js");
            gatewayContext.getWebResourceManager()
                    .getNavigationModel()
                    .getHome()
                    .addCategory("ladderlogic", cat -> cat
                            .label("Ladder Logic Studio")
                            .addPage("Programs", page -> page
                                    .position(150)
                                    .mount("/ladder-programs", "LadderLogicStudio", jsModule)
                            )
                    );
            logger.info("Ladder Logic Studio Gateway Navigation registered at /app/ladder-programs");
        } catch (Exception e) {
            logger.error("Failed registering Gateway navigation for Ladder Logic Studio", e);
        }
    }

    @Override
    public void startup(LicenseState licenseState) {
        logger.info("Starting Ladder Logic Studio Module...");
        try {
            ManagedTagProviderConfiguration providerConfig = ManagedTagProviderConfiguration
                    .builder(TagImageManager.MANAGED_PROVIDER_NAME)
                    .allowTagDeletion(true)
                    .allowTagCustomization(true)
                    .persistTags(false)
                    .allowDefaultWriteHandling(true)
                    .hasDataTypes(true)
                    .build();

            this.managedTagProvider = context.getTagManager().getOrCreateManagedProvider(providerConfig);

            File dataDir = context.getSystemManager().getDataDir();
            File projectFile = new File(new File(dataDir, "ladder-plc"), "project.json");

            this.tagImageManager = new TagImageManager(context.getTagManager(), this.managedTagProvider);
            this.scanEngine = new CyclicScanEngine(this.tagImageManager, projectFile);
            this.scanEngine.start();

            context.getWebResourceManager().addServlet(SERVLET_MOUNT, LadderStudioServlet.class);
            logger.info("Ladder Logic Studio Web IDE mounted at /system/{}", SERVLET_MOUNT);
        } catch (Exception e) {
            logger.error("Failed starting Ladder Logic Studio module", e);
        }
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down Ladder Logic Studio Module...");
        try {
            if (context != null && context.getWebResourceManager() != null) {
                context.getWebResourceManager().removeServlet(SERVLET_MOUNT);
            }
        } catch (Exception ignored) {
        }
        if (scanEngine != null) {
            scanEngine.stop();
        }
        if (tagImageManager != null) {
            tagImageManager.shutdown();
        }
        if (managedTagProvider != null) {
            try {
                managedTagProvider.shutdown(false);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public Optional<String> getMountPathAlias() {
        return Optional.of("ladderplc");
    }

    @Override
    public Optional<String> getMountedResourceFolder() {
        return Optional.of("web");
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }

    @Override
    public boolean isMakerEditionCompatible() {
        return true;
    }

    public GatewayContext getContext() {
        return context;
    }

    public TagImageManager getTagImageManager() {
        return tagImageManager;
    }

    public CyclicScanEngine getScanEngine() {
        return scanEngine;
    }
}
