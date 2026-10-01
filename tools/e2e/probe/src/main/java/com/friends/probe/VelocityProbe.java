package com.friends.probe;

import org.slf4j.Logger;

import com.friends.api.FriendsAPI;
import com.friends.api.velocity.FriendAddEvent;
import com.friends.api.velocity.FriendAddedEvent;
import com.friends.api.velocity.FriendRemoveEvent;
import com.friends.api.velocity.FriendRemovedEvent;
import com.friends.api.velocity.FriendRequestSendEvent;
import com.friends.api.velocity.FriendStatusChangeEvent;
import com.google.inject.Inject;
import com.velocitypowered.api.event.ResultedEvent.GenericResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;

/** Velocity registers the main instance as a listener by itself. */
public final class VelocityProbe {
    private final Logger log;
    private FriendsAPI api;

    @Inject
    public VelocityProbe(Logger log) {
        this.log = log;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        api = FriendsAPI.get(); // Friends (a dependency) initialised first
        log("probe: api ok");
    }

    private void log(String line) {
        log.info(line);
    }

    @Subscribe
    public void on(FriendRequestSendEvent e) {
        log(Probe.pair("FriendRequestSendEvent", e.getPlayerName(), e.getTargetName()));
    }

    @Subscribe
    public void on(FriendAddEvent e) {
        log(Probe.pair("FriendAddEvent", e.getPlayerName(), e.getTargetName()));
    }

    @Subscribe
    public void on(FriendAddedEvent e) {
        log(Probe.pair("FriendAddedEvent", e.getPlayerName(), e.getTargetName()));
        Probe.befriended(api, e.getPlayerId(), e.getPlayerName(), e.getTargetId(), this::log, Runnable::run);
    }

    @Subscribe
    public void on(FriendRemoveEvent e) {
        log(Probe.pair("FriendRemoveEvent", e.getPlayerName(), e.getTargetName()));
    }

    @Subscribe
    public void on(FriendRemovedEvent e) {
        log(Probe.pair("FriendRemovedEvent", e.getPlayerName(), e.getTargetName()));
    }

    @Subscribe
    public void on(FriendStatusChangeEvent e) {
        if (Probe.cancels(e.getNewStatus())) e.setResult(GenericResult.denied());
        log(Probe.status(e.getPlayerName(), e.getOldStatus(), e.getNewStatus()));
    }
}
