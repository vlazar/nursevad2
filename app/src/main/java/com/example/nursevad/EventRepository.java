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
        if (current != null) {
            current.add(event);
            liveEvents.postValue(current);
        }
    }

    public void clearEvents() {
        liveEvents.postValue(new ArrayList<>());
    }

    public MutableLiveData<List<LogEvent>> getLiveEvents() { return liveEvents; }
}