package com.mobicrave.eventtracker.index;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.mobicrave.eventtracker.list.DmaIdList;
import com.mobicrave.eventtracker.list.IdList;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

public class UserEventIndex implements Closeable {
  private final String directory;
  private LoadingCache<Integer, IdList> index;
  private int numRecords;

  private UserEventIndex(String directory, LoadingCache<Integer, IdList> index, int numRecords) {
    this.directory = directory;
    this.index = index;
    this.numRecords = numRecords;
  }

  public void enumerateEventIds(int userId, long firstStepEventId, long maxLastEventId,
      Callback callback) {
    IdList.Iterator eventIdIterator = index.getUnchecked(userId).subList(firstStepEventId, maxLastEventId);
    while (eventIdIterator.hasNext()) {
      if (!callback.onEventId(eventIdIterator.next())) {
        return;
      }
    }
  }

  public void addEvent(int userId, long eventId) {
    index.getUnchecked(userId).add(eventId);
  }

  @Override
  public void close() throws IOException {
    //noinspection ResultOfMethodCallIgnored
    new File(directory).mkdirs();
    try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(
        getSerializationFile(directory)))) {
      oos.writeInt(numRecords);
    }
    index.invalidateAll();
  }

  private static String getSerializationFile(String directory) {
    return directory + "/user_event_index.ser";
  }

  private static String getIdListSerializationFile(String directory, long id) {
    return String.format("%s/%d/%d/%d/%d.ser", directory, id % 100, id / 100 % 100,
        id / 10000 % 100, id);
  }

  public static UserEventIndex build(final String directory) {
    File file = new File(getSerializationFile(directory));
    LoadingCache<Integer, IdList> index = CacheBuilder.newBuilder()
        .maximumSize(5000)
        .removalListener(new RemovalListener<Integer, IdList>() {
          @Override
          public void onRemoval(RemovalNotification<Integer, IdList> notification) {
            try {
              notification.getValue().close();
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        })
        .build(new CacheLoader<Integer, IdList>() {
          @Override
          public IdList load(Integer key) throws Exception {
            return DmaIdList.build(getIdListSerializationFile(directory, key), 100);
          }
        });
    if (file.exists()) {
      try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
        int numRecords =  ois.readInt();
        return new UserEventIndex(directory, index, numRecords);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    return new UserEventIndex(directory, index, 0);
  }

  public static interface Callback {
    // return shouldContinue
    public boolean onEventId(long eventId);
  }
}