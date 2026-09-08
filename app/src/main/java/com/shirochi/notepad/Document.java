package com.shirochi.notepad;

import com.shirochi.notepad.core.EditHistory;
import java.util.UUID;

/** One independent editor tab. External files change only on an explicit save. */
final class Document {
  String id = UUID.randomUUID().toString();
  String name = "Untitled.txt";
  String uri = "";
  String text = "";
  String savedText = "";
  String diskHash = "";
  String encoding = "UTF-8";
  String lineEnding = "LF";
  String savedFormat = "UTF-8|LF|false";
  String language = "Auto";
  boolean bom;
  int start, end, scrollY;
  final EditHistory history = new EditHistory(100);

  boolean dirty() {
    return !text.equals(savedText) || !format().equals(savedFormat);
  }

  String format() {
    return encoding + "|" + lineEnding + "|" + bom;
  }

  void markSaved(String content, String format) {
    savedText = content;
    savedFormat = format;
  }

  String syntax() {
    if (!language.equals("Auto")) return language;
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "txt" : name.substring(dot + 1);
  }
}
