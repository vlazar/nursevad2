package com.example.nursevad;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

public class EventBus {
    private static EventBus instance;
    private final MutableLiveData<Integer> volume = new MutableLiveData<>(0);
    private final MutableLiveData<String> status = new MutableLiveData<>("Idle");
    private final MutableLiveData<String> debug = new MutableLiveData<>("");
    private final MutableLiveData<String> playingUri = new MutableLiveData<>(null);
    private final MutableLiveData<Boolean> vadRunning = new MutableLiveData<>(false); // NEW

    private EventBus() {}

    public static synchronized EventBus getInstance() {
        if (instance == null) instance = new EventBus();
        return instance;
    }

    public void postVolume(int vol) { volume.postValue(vol); }
    public LiveData<Integer> getVolume() { return volume; }

    public void postStatus(String stat) { status.postValue(stat); }
    public LiveData<String> getStatus() { return status; }

    public void postDebug(String msg) { debug.postValue(msg); }
    public LiveData<String> getDebug() { return debug; }

    public void postPlayingUri(String uri) { playingUri.postValue(uri); }
    public LiveData<String> getPlayingUri() { return playingUri; }

    public void postVadRunning(boolean running) { vadRunning.postValue(running); } // NEW
    public LiveData<Boolean> getVadRunning() { return vadRunning; }               // NEW
}