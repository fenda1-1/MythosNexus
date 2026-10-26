package com.mythos.nexus.core;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/**
 * Coremod entry for Mythos Nexus. Installs the ASM transformer that routes
 * packets to the correct per-bot NetHandlerPlayClient.
 */
@IFMLLoadingPlugin.Name("MythosNexus")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(1500)
public class NexusPlugin implements IFMLLoadingPlugin {

    public static final Logger LOGGER = LogManager.getLogger("MythosNexus");

    @Override
    public String[] getASMTransformerClass() {
        return new String[]{ NexusTransformer.class.getName() };
    }

    @Override public String getModContainerClass() { return null; }
    @Override public String getSetupClass() { return null; }
    @Override public void injectData(Map<String, Object> data) { }
    @Override public String getAccessTransformerClass() { return null; }
}
