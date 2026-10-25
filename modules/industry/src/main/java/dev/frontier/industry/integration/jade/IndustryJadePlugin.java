package dev.frontier.industry.integration.jade;

import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.energy.ControllerBlockEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

@WailaPlugin
public final class IndustryJadePlugin implements IWailaPlugin {
    @Override public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(IndustryJadeProvider.INSTANCE, ControllerBlockEntity.class);
        registration.registerEnergyStorage(PrivateEnergyProvider.INSTANCE, ControllerBlockEntity.class);
        registration.registerBlockDataProvider(PrivateEnergyProvider.INSTANCE, ControllerBlockEntity.class);
    }

    @Override public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(IndustryJadeProvider.INSTANCE, IndustryContent.Controller.class);
    }
}
