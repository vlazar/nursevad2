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
        DebugLogger.log("EventRepo ADD: type=" + event.type + " | id=" + event.id + 
                " | ts=" + event.timestamp + " | level=" + event.level + 
                " | isPoni=" + event.isPoni + " | display=" + event.displayName +
                " | thread=" + Thread.currentThread().getName());
        
        synchronized (masterList) {
            masterList.add(0, event);
            DebugLogger.log("EventRepo masterList size=" + masterList.size());
            liveEvents.postValue(new ArrayList<>(masterList));
        }
    }

    public void clearEvents() {
        DebugLogger.log("EventRepo CLEAR");
        synchronized (masterList) {
            masterList.clear();
            liveEvents.postValue(new ArrayList<>());
        }
    }

    public MutableLiveData<List<LogEvent>> getLiveEvents() { return liveEvents; }
}