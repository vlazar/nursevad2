package com.example.nursevad;

import androidx.lifecycle.MutableLiveData;
import java.util.ArrayList;
import java.util.List;

public class EventRepository {
    private static EventRepository instance;
    private final List<LogEvent> masterList = new ArrayList<>();
    private final MutableLiveData<List<LogEvent>> liveEvents = new MutableLiveData<>();

    public static EventRepository getInstance() {
        if (instance == null) instance = new EventRepository();
        return instance;
    }

    public void addEvent(LogEvent event) {
        DebugLogger.log("EventRepository addEvent: " + event.type + " | ID: " + event.id);
        synchronized (masterList) {
            masterList.add(0, event); // Add to top
            liveEvents.postValue(new ArrayList<>(masterList)); // Post a fresh copy
        }
        DebugLogger.log("EventRepository new list size: " + masterList.size());
    }

    public void clearEvents() {
        synchronized (masterList) {
            masterList.clear();
            liveEvents.postValue(new ArrayList<>());
        }
    }

    public MutableLiveData<List<LogEvent>> getLiveEvents() { return liveEvents; }
}