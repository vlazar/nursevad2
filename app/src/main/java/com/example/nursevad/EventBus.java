package com.example.nursevad;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

public class EventBus {
    private static EventBus instance;
    private final MutableLiveData<Integer> volume = new MutableLiveData<>(0);
    private final MutableLiveData<String> status = new MutableLiveData<>("Idle");

    private EventBus() {}

    public static synchronized EventBus getInstance() {
        if (instance == null) {
            instance = new EventBus();
        }
        return instance;
    }

    public void postVolume(int vol) {
        volume.postValue(vol);
    }

    public LiveData<Integer> getVolume() {
        return volume;
    }

    public void postStatus(String stat) {
        status.postValue(stat);
    }

    public LiveData<String> getStatus() {
        return status;
    }
}