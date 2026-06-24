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
        List<LogEvent> current = liveEvents.getValue();
        List<LogEvent> newList = new ArrayList<>();
        
        // Add new event at the TOP (index 0)
        newList.add(event); 
        
        if (current != null) {
            newList.addAll(current);
        }
        liveEvents.postValue(newList);
    }

    public void clearEvents() {
        liveEvents.postValue(new ArrayList<>());
    }

    public MutableLiveData<List<LogEvent>> getLiveEvents() { return liveEvents; }
}