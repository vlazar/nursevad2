package com.example.nursevad;

import androidx.lifecycle.MutableLiveData;
import java.util.ArrayList;
import java.util.List;

public class EventRepository {
    private static EventRepository instance;
    private static final int MAX_EVENTS = 100;
    private final List<LogEvent> masterList = new ArrayList<>();
    private final MutableLiveData<List<LogEvent>> liveEvents = new MutableLiveData<>();

    public static EventRepository getInstance() {
        if (instance == null) instance = new EventRepository();
        return instance;
    }

    public void addEvent(LogEvent event) {
        DebugLogger.log("EventRepository addEvent: type=" + event.type + 
                " | id=" + event.id + " | thread=" + Thread.currentThread().getName());
        
        synchronized (masterList) {
            masterList.add(0, event);
            
            // Cap the list to prevent unbounded memory growth
            while (masterList.size() > MAX_EVENTS) {
                masterList.remove(masterList.size() - 1);
            }
            
            liveEvents.postValue(new ArrayList<>(masterList));
        }
        
        DebugLogger.log("EventRepository masterList size: " + masterList.size());
    }

    public void clearEvents() {
        DebugLogger.log("EventRepository CLEAR");
        synchronized (masterList) {
            masterList.clear();
            liveEvents.postValue(new ArrayList<>());
        }
    }

    public MutableLiveData<List<LogEvent>> getLiveEvents() { return liveEvents; }
}