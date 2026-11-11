package com.github.moyingshi.osc.tv.event;

import com.github.moyingshi.osc.tv.bean.Config;
import com.github.moyingshi.osc.tv.bean.Device;
import com.github.moyingshi.osc.tv.bean.History;

import org.greenrobot.eventbus.EventBus;

public record CastEvent(Config config, Device device, History history) {

    public static void post(Config config, Device device, History history) {
        EventBus.getDefault().post(new CastEvent(config, device, history));
    }
}
