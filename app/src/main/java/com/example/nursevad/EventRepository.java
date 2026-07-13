package com.example.nursevad;

import androidx.lifecycle.MutableLiveData;
import java.util.ArrayList;
import java.util.List;

public class EventRepository {
    private static EventRepository instance;
    private MutableLiveData<List<LogEvent>> liveEvents = new MutableLiveData<>(new ArrayList<>());

    public static EventRepository getInstance() {
        if (instance == null) instance = new EventRepository();
        return instance;
    }

    public void addEvent(LogEvent event) {
        DebugLogger.log("EventRepository addEvent: " + event.type + " | ID: " + event.id);
        List<LogEvent> current = liveEvents.getValue();
        List<LogEvent> newList = new ArrayList<>();
        newList.add(event); 
        if (current != null) {
            newList.addAll(current);
        }
        DebugLogger.log("EventRepository new list size: " + newList.size());
        liveEvents.postValue(newList);
    }

    public void clearEvents() {
        liveEvents.postValue(new ArrayList<>());
    }

    public MutableLiveData<List<LogEvent>> getLiveEvents() { return liveEvents; }
}