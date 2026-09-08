package com.shirochi.notepad.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class EditHistoryTest {
  @Test
  public void undoRedoRestoresTextAndSelections() {
    EditHistory history = new EditHistory(10);
    history.reset("one", 1, 2);
    history.record("two", 2, 3);
    EditHistory.Snapshot undone = history.undo();
    assertEquals("one", undone.text);
    assertEquals(1, undone.start);
    assertEquals(2, undone.end);
    assertEquals("two", history.redo().text);
    assertNull(history.redo());
  }

  @Test
  public void newEditAfterUndoDropsFutureBranch() {
    EditHistory history = new EditHistory(10);
    history.reset("a", 1, 1);
    history.record("b", 1, 1);
    history.undo();
    history.record("c", 1, 1);
    assertFalse(history.canRedo());
    assertEquals("a", history.undo().text);
    assertEquals("c", history.redo().text);
  }

  @Test
  public void selectionOnlyChangePreservesRedoAndUpdatesCursor() {
    EditHistory history = new EditHistory(10);
    history.reset("abc", 3, 3);
    history.record("abcd", 4, 4);
    history.undo();
    history.record("abc", 1, 1);
    assertFalse(history.canUndo());
    assertTrue(history.canRedo());
    assertEquals(1, history.current().start);
  }

  @Test
  public void updateSelectionCapturesPositionBeforeEdit() {
    EditHistory history = new EditHistory(10);
    history.reset("abc", 3, 3);
    history.updateSelection(1, 2);
    history.record("aXc", 2, 2);
    EditHistory.Snapshot snapshot = history.undo();
    assertEquals(1, snapshot.start);
    assertEquals(2, snapshot.end);
  }

  @Test
  public void entryLimitDiscardsOldestSnapshots() {
    EditHistory history = new EditHistory(3);
    history.reset("a", 1, 1);
    history.record("b", 1, 1);
    history.record("c", 1, 1);
    history.record("d", 1, 1);
    assertEquals("c", history.undo().text);
    assertEquals("b", history.undo().text);
    assertNull(history.undo());
  }

  @Test
  public void characterBudgetRetainsCurrentEvenWhenOversized() {
    EditHistory history = new EditHistory(10, 5);
    history.record("abc", 3, 3);
    history.record("def", 3, 3);
    assertFalse(history.canUndo());
    history.record("0123456789", 10, 10);
    assertEquals("0123456789", history.current().text);
    assertFalse(history.canUndo());
  }

  @Test
  public void resetClearsUndoRedo() {
    EditHistory history = new EditHistory(10);
    history.record("one", 1, 1);
    history.record("two", 1, 1);
    history.undo();
    history.reset("new", 0, 0);
    assertFalse(history.canUndo());
    assertFalse(history.canRedo());
    assertEquals("new", history.current().text);
  }

  @Test
  public void snapshotSelectionsAreClamped() {
    EditHistory.Snapshot snapshot = new EditHistory.Snapshot("abc", -1, 100);
    assertEquals(0, snapshot.start);
    assertEquals(3, snapshot.end);
  }
}
