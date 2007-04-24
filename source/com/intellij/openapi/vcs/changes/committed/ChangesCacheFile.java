package com.intellij.openapi.vcs.changes.committed;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vcs.CachingCommittedChangesProvider;
import com.intellij.openapi.vcs.RepositoryLocation;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FilePathImpl;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.update.UpdatedFiles;
import com.intellij.openapi.vcs.update.FileGroup;
import com.intellij.openapi.vcs.versionBrowser.ChangeBrowserSettings;
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.FileNotFoundException;
import java.util.*;

/**
 * @author yole
 */
public class ChangesCacheFile {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.vcs.changes.committed.ChangesCacheFile");
  private static final int VERSION = 3;

  private File myPath;
  private File myIndexPath;
  private RandomAccessFile myStream;
  private RandomAccessFile myIndexStream;
  private boolean myStreamsOpen;
  private Project myProject;
  private CachingCommittedChangesProvider myChangesProvider;
  private FilePath myRootPath;
  private RepositoryLocation myLocation;
  private Date myFirstCachedDate;
  private Date myLastCachedDate;
  private long myFirstCachedChangelist = Long.MAX_VALUE;
  private boolean myHaveCompleteHistory = false;
  private boolean myHeaderLoaded = false;
  @NonNls private static final String INDEX_EXTENSION = ".index";
  private static final int INDEX_ENTRY_SIZE = 3*8+2;
  private static final int HEADER_SIZE = 30;

  public ChangesCacheFile(Project project, File path, CachingCommittedChangesProvider changesProvider, VirtualFile root,
                          RepositoryLocation location) {
    final Calendar date = Calendar.getInstance();
    date.set(2020, 1, 2);
    myFirstCachedDate = date.getTime();
    date.set(1970, 1, 2);
    myLastCachedDate = date.getTime();
    myProject = project;
    myPath = path;
    myIndexPath = new File(myPath.toString() + INDEX_EXTENSION);
    myChangesProvider = changesProvider;
    myRootPath = new FilePathImpl(root);
    myLocation = location;
  }

  public RepositoryLocation getLocation() {
    return myLocation;
  }

  public CachingCommittedChangesProvider getProvider() {
    return myChangesProvider;
  }

  public boolean isEmpty() {
    if (!myPath.exists()) {
      return true;
    }
    try {
      loadHeader();
    }
    catch(VersionMismatchException ex) {
      myPath.delete();
      myIndexPath.delete();
      return true;
    }

    return false;
  }

  public List<CommittedChangeList> writeChanges(final List<CommittedChangeList> changes, boolean assumeCompletelyDownloaded) throws IOException {
    List<CommittedChangeList> result = new ArrayList<CommittedChangeList>(changes.size());
    boolean wasEmpty = isEmpty();
    openStreams();
    try {
      if (wasEmpty) {
        writeHeader();
      }
      myStream.seek(myStream.length());
      IndexEntry[] entries = readLastIndexEntries(0, 1);
      // the list and index are sorted in direct chronological order
      Collections.sort(changes, new Comparator<CommittedChangeList>() {
        public int compare(final CommittedChangeList o1, final CommittedChangeList o2) {
          return o1.getCommitDate().compareTo(o2.getCommitDate());
        }
      });
      for(CommittedChangeList list: changes) {
        boolean duplicate = false;
        for(IndexEntry entry: entries) {
          if (list.getCommitDate().getTime() == entry.date && list.getNumber() == entry.number) {
            duplicate = true;
            break;
          }
        }
        if (duplicate) continue;
        result.add(list);
        long position = myStream.getFilePointer();
        //noinspection unchecked
        myChangesProvider.writeChangeList(myStream, list);
        if (list.getCommitDate().getTime() > myLastCachedDate.getTime()) {
          myLastCachedDate = list.getCommitDate();
        }
        if (list.getCommitDate().getTime() < myFirstCachedDate.getTime()) {
          myFirstCachedDate = list.getCommitDate();
        }
        if (list.getNumber() < myFirstCachedChangelist) {
          myFirstCachedChangelist = list.getNumber(); 
        }
        writeIndexEntry(list.getNumber(), list.getCommitDate().getTime(), position, assumeCompletelyDownloaded);
      }
      writeHeader();
      myHeaderLoaded = true;
    }
    finally {
      closeStreams();
    }
    return result;
  }

  private void writeIndexEntry(long number, long date, long offset, boolean completelyDownloaded) throws IOException {
    myIndexStream.writeLong(number);
    myIndexStream.writeLong(date);
    myIndexStream.writeLong(offset);
    myIndexStream.writeShort(completelyDownloaded ? 1 : 0);
  }

  private void openStreams() throws FileNotFoundException {
    myStream = new RandomAccessFile(myPath, "rw");
    myIndexStream = new RandomAccessFile(myIndexPath, "rw");
    myStreamsOpen = true;
  }

  private void closeStreams() throws IOException {
    myStreamsOpen = false;
    try {
      myStream.close();
    }
    finally {
      myIndexStream.close();
    }
  }

  private void writeHeader() throws IOException {
    assert myStreamsOpen;
    myStream.seek(0);
    myStream.writeInt(VERSION);
    myStream.writeLong(myLastCachedDate.getTime());
    myStream.writeLong(myFirstCachedDate.getTime());
    myStream.writeLong(myFirstCachedChangelist);
    myStream.writeShort(myHaveCompleteHistory ? 1 : 0);
  }

  private IndexEntry[] readLastIndexEntries(int offset, int count) throws IOException {
    if (!myIndexPath.exists()) {
      return NO_ENTRIES;
    }
    long totalCount = myIndexStream.length() / INDEX_ENTRY_SIZE;
    if (count > totalCount - offset) {
      count = (int)totalCount - offset;
    }
    if (count == 0) {
      return NO_ENTRIES;
    }
    myIndexStream.seek(myIndexStream.length() - INDEX_ENTRY_SIZE * (count + offset));
    IndexEntry[] result = new IndexEntry[count];
    for(int i=0; i<count; i++) {
      result [i] = new IndexEntry();
      readIndexEntry(result [i]);
    }
    return result;
  }

  private void readIndexEntry(final IndexEntry result) throws IOException {
    result.number = myIndexStream.readLong();
    result.date = myIndexStream.readLong();
    result.offset = myIndexStream.readLong();
    result.completelyDownloaded = (myIndexStream.readShort() != 0);
  }

  public Date getLastCachedDate() {
    loadHeader();
    return myLastCachedDate;
  }

  public Date getFirstCachedDate() {
    loadHeader();
    return myFirstCachedDate;
  }

  public long getFirstCachedChangelist() {
    loadHeader();
    return myFirstCachedChangelist;
  }

  private void loadHeader() {
    if (!myHeaderLoaded) {
      try {
        RandomAccessFile stream = new RandomAccessFile(myPath, "r");
        try {
          int version = stream.readInt();
          if (version != VERSION) {
            throw new VersionMismatchException();
          }
          myLastCachedDate = new Date(stream.readLong());
          myFirstCachedDate = new Date(stream.readLong());
          myFirstCachedChangelist = stream.readLong();
          myHaveCompleteHistory = (stream.readShort() != 0);
          assert stream.getFilePointer() == HEADER_SIZE;
        }
        finally {
          stream.close();
        }
      }
      catch (IOException e) {
        LOG.error(e);
      }
      myHeaderLoaded = true;
    }
  }

  public List<CommittedChangeList> readChanges(final ChangeBrowserSettings settings, final int maxCount) throws IOException {
    final List<CommittedChangeList> result = new ArrayList<CommittedChangeList>();
    final ChangeBrowserSettings.Filter filter = settings.createFilter();
    openStreams();
    try {
      if (maxCount == 0) {
        myStream.seek(HEADER_SIZE);  // skip header
        while(myStream.getFilePointer() < myStream.length()) {
          CommittedChangeList changeList = myChangesProvider.readChangeList(myLocation, myStream);
          if (filter.accepts(changeList)) {
            result.add(changeList);
          }
        }
      }
      else if (!settings.isAnyFilterSpecified()) {
        IndexEntry[] entries = readLastIndexEntries(0, maxCount);
        for(IndexEntry entry: entries) {
          myStream.seek(entry.offset);
          result.add(myChangesProvider.readChangeList(myLocation, myStream));
        }
      }
      else {
        int offset = 0;
        while(result.size() < maxCount) {
          IndexEntry[] entries = readLastIndexEntries(offset, 1);
          if (entries.length == 0) {
            break;
          }
          CommittedChangeList changeList = loadChangeListAt(entries [0].offset);
          if (filter.accepts(changeList)) {
            result.add(0, changeList);
          }
          offset++;
        }
      }
      return result;
    }
    finally {
      closeStreams();
    }
  }

  public boolean hasCompleteHistory() {
    return myHaveCompleteHistory;
  }

  public void setHaveCompleteHistory(final boolean haveCompleteHistory) {
    if (myHaveCompleteHistory != haveCompleteHistory) {
      myHaveCompleteHistory = haveCompleteHistory;
      try {
        openStreams();
        try {
          writeHeader();
        }
        finally {
          closeStreams();
        }
      }
      catch(IOException ex) {
        LOG.error(ex);
      }
    }
  }

  public Collection<? extends CommittedChangeList> loadIncomingChanges() throws IOException {
    final List<CommittedChangeList> result = new ArrayList<CommittedChangeList>();
    int offset = 0;
    openStreams();
    try {
      while(true) {
        IndexEntry[] entries = readLastIndexEntries(offset, 1);
        if (entries.length == 0 || entries [0].completelyDownloaded) {
          break;
        }
        result.add(loadChangeListAt(entries[0].offset));
        offset++;
      }
      return result;
    }
    finally {
      closeStreams();
    }
  }

  private CommittedChangeList loadChangeListAt(final long clOffset) throws IOException {
    myStream.seek(clOffset);
    return myChangesProvider.readChangeList(myLocation, myStream);
  }

  public boolean processUpdatedFiles(final UpdatedFiles updatedFiles) throws IOException {
    boolean haveUnaccountedUpdatedFiles = false;
    openStreams();
    try {
      final List<IncomingChangeListData> incomingData = loadIncomingChangeListData();
      for(FileGroup group: updatedFiles.getTopLevelGroups()) {
        haveUnaccountedUpdatedFiles |= processGroup(group, incomingData);
      }
      if (!haveUnaccountedUpdatedFiles) {
        for(IncomingChangeListData data: incomingData) {
          if (data.accountedChanges.size() == data.changeList.getChanges().size()) {
            myIndexStream.seek(data.indexOffset);
            writeIndexEntry(data.indexEntry.number, data.indexEntry.date, data.indexEntry.offset, true);
          }
        }
      }
    }
    finally {
      closeStreams();
    }
    return haveUnaccountedUpdatedFiles;
  }

  private boolean processGroup(final FileGroup group, final List<IncomingChangeListData> incomingData) {
    boolean haveUnaccountedUpdatedFiles = false;
    final List<Pair<String,VcsRevisionNumber>> list = group.getFilesAndRevisions(myProject);
    for(Pair<String, VcsRevisionNumber> pair: list) {
      haveUnaccountedUpdatedFiles |= processFile(pair.first, pair.second, incomingData);
    }
    for(FileGroup childGroup: group.getChildren()) {
      haveUnaccountedUpdatedFiles |= processGroup(childGroup, incomingData);
    }
    return haveUnaccountedUpdatedFiles;
  }

  private boolean processFile(final String file, @NotNull final VcsRevisionNumber number, final List<IncomingChangeListData> incomingData) {
    FilePath path = new FilePathImpl(new File(file), false);
    if (!path.isUnder(myRootPath, false)) {
      return false;
    }
    boolean foundRevision = false;
    for(IncomingChangeListData data: incomingData) {
      for(Change change: data.changeList.getChanges()) {
        ContentRevision afterRevision = change.getAfterRevision();
        if (afterRevision != null && afterRevision.getFile().equals(path)) {
          int rc = number.compareTo(afterRevision.getRevisionNumber());
          if (rc == 0) {
            foundRevision = true;
            data.accountedChanges.add(change);
          }
        }
      }
    }
    return !foundRevision;
  }

  private List<IncomingChangeListData> loadIncomingChangeListData() throws IOException {
    final long length = myIndexStream.length();
    long totalCount = length / INDEX_ENTRY_SIZE;
    List<IncomingChangeListData> incomingData = new ArrayList<IncomingChangeListData>();
    for(int i=0; i<totalCount; i++) {
      final long indexOffset = length - (i + 1) * INDEX_ENTRY_SIZE;
      myIndexStream.seek(indexOffset);
      IndexEntry e = new IndexEntry();
      readIndexEntry(e);
      if (e.completelyDownloaded) break;
      IncomingChangeListData data = new IncomingChangeListData();
      data.indexOffset = indexOffset;
      data.indexEntry = e;
      data.changeList = loadChangeListAt(e.offset);
      data.accountedChanges = new HashSet<Change>();
      incomingData.add(data);
    }
    return incomingData;
  }

  private static class IndexEntry {
    long number;
    long date;
    long offset;
    boolean completelyDownloaded;
  }

  private static class IncomingChangeListData {
    public long indexOffset;
    public IndexEntry indexEntry;
    public CommittedChangeList changeList;
    public Set<Change> accountedChanges;
  }

  private static final IndexEntry[] NO_ENTRIES = new IndexEntry[0];

  private static class VersionMismatchException extends RuntimeException {
  }
}
