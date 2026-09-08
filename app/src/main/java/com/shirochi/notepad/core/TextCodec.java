package com.shirochi.notepad.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Lossless supported-encoding I/O. Unicode BOMs take precedence; BOM-less UTF-16 is recognized from
 * alternating NULs, then strict UTF-8 is tried, with strict Windows-1252 as a legacy fallback.
 * Other platform-supported character sets can be explicitly selected. Unmappable bytes/text and
 * binary controls are rejected, never replaced with question marks. Mixed line endings are
 * normalized in memory and saved using the detected dominant line ending.
 */
public final class TextCodec {
  private TextCodec() {}

  public static final class Decoded {
    public final String text;
    public final String encoding;
    public final String lineEnding;
    public final boolean bom;

    public Decoded(String text, String encoding, String lineEnding, boolean bom) {
      this.text = text;
      this.encoding = encoding;
      this.lineEnding = lineEnding;
      this.bom = bom;
    }
  }

  public static Decoded decode(byte[] bytes) throws IOException {
    Objects.requireNonNull(bytes, "bytes");
    String encoding;
    boolean bom = false;
    int offset = 0;
    if (startsWith(bytes, 0x00, 0x00, 0xfe, 0xff) || startsWith(bytes, 0xff, 0xfe, 0x00, 0x00)) {
      throw new IOException("UTF-32 files are not supported. Convert the file to UTF-8 or UTF-16.");
    } else if (startsWith(bytes, 0xef, 0xbb, 0xbf)) {
      encoding = "UTF-8";
      bom = true;
      offset = 3;
    } else if (startsWith(bytes, 0xff, 0xfe)) {
      encoding = "UTF-16LE";
      bom = true;
      offset = 2;
    } else if (startsWith(bytes, 0xfe, 0xff)) {
      encoding = "UTF-16BE";
      bom = true;
      offset = 2;
    } else {
      encoding = guessUtf16(bytes);
      if (encoding == null) encoding = "UTF-8";
    }
    String raw;
    try {
      raw = decodeStrict(bytes, offset, encoding);
    } catch (CharacterCodingException exception) {
      if (bom || !encoding.equals("UTF-8")) {
        throw new IOException("The file contains invalid " + encoding + " data.", exception);
      }
      encoding = "windows-1252";
      try {
        raw = decodeStrict(bytes, 0, encoding);
      } catch (CharacterCodingException fallbackError) {
        throw new IOException(
            "The file is not valid UTF-8, UTF-16, or Windows-1252 text.", fallbackError);
      }
    }
    rejectBinary(raw);
    String lineEnding = detectLineEnding(raw);
    return new Decoded(normalize(raw), encoding, lineEnding, bom);
  }

  /**
   * Reads using a user-selected character set, with no automatic fallback. Matching Unicode BOMs
   * are retained as document metadata rather than text. Charset aliases are returned as canonical
   * names.
   */
  public static Decoded decode(byte[] bytes, String encoding) throws IOException {
    Objects.requireNonNull(bytes, "bytes");
    Charset charset = resolveCharset(encoding);
    String canonical = charset.name();
    if ("UTF-16".equals(canonical)) {
      canonical = startsWith(bytes, 0xff, 0xfe) ? "UTF-16LE" : "UTF-16BE";
    }
    if (startsWith(bytes, 0x00, 0x00, 0xfe, 0xff) || startsWith(bytes, 0xff, 0xfe, 0x00, 0x00)) {
      throw new IOException("UTF-32 files are not supported. Convert the file to UTF-8 or UTF-16.");
    }
    int offset = 0;
    if ("UTF-8".equals(canonical) && startsWith(bytes, 0xef, 0xbb, 0xbf)) {
      offset = 3;
    } else if ("UTF-16LE".equals(canonical)) {
      if (startsWith(bytes, 0xfe, 0xff)) {
        throw new IOException("This file has a UTF-16BE byte-order mark. Choose UTF-16BE.");
      }
      if (startsWith(bytes, 0xff, 0xfe)) offset = 2;
    } else if ("UTF-16BE".equals(canonical)) {
      if (startsWith(bytes, 0xff, 0xfe)) {
        throw new IOException("This file has a UTF-16LE byte-order mark. Choose UTF-16LE.");
      }
      if (startsWith(bytes, 0xfe, 0xff)) offset = 2;
    }
    String raw;
    try {
      raw = decodeStrict(bytes, offset, canonical);
    } catch (CharacterCodingException exception) {
      throw new IOException("The file contains invalid " + canonical + " data.", exception);
    }
    rejectBinary(raw);
    return new Decoded(normalize(raw), canonical, detectLineEnding(raw), offset > 0);
  }

  /**
   * Canonical, writable character sets available on this device, with common choices first. The
   * explicit UTF-16 byte orders avoid charsets that silently insert a BOM when saving. UTF-32
   * remains unsupported by the editor's automatic detection and is intentionally omitted.
   */
  public static List<String> availableEncodings() {
    Set<String> result = new LinkedHashSet<>();
    String[] preferred = {
      "UTF-8",
      "UTF-16LE",
      "UTF-16BE",
      "windows-1252",
      "US-ASCII",
      "windows-1250",
      "windows-1251",
      "windows-1253",
      "windows-1254",
      "windows-1255",
      "windows-1256",
      "windows-1257",
      "windows-1258",
      "ISO-8859-1",
      "ISO-8859-2",
      "ISO-8859-3",
      "ISO-8859-4",
      "ISO-8859-5",
      "ISO-8859-6",
      "ISO-8859-7",
      "ISO-8859-8",
      "ISO-8859-9",
      "ISO-8859-10",
      "ISO-8859-11",
      "ISO-8859-13",
      "ISO-8859-14",
      "ISO-8859-15",
      "ISO-8859-16",
      "Shift_JIS",
      "windows-31j",
      "EUC-JP",
      "ISO-2022-JP",
      "GB18030",
      "GBK",
      "GB2312",
      "Big5",
      "EUC-KR",
      "x-windows-949",
      "KOI8-R",
      "KOI8-U"
    };
    for (String name : preferred) addEncoding(result, name);
    for (String name : Charset.availableCharsets().keySet()) addEncoding(result, name);
    return Collections.unmodifiableList(new ArrayList<>(result));
  }

  private static void addEncoding(Set<String> names, String encoding) {
    try {
      String canonical = resolveCharset(encoding).name();
      if (!"UTF-16".equals(canonical)) names.add(canonical);
    } catch (IOException ignored) {
      // Charset availability differs between desktop Java and Android versions.
    }
  }

  public static byte[] encode(String text, String encoding, String lineEnding, boolean bom)
      throws IOException {
    Objects.requireNonNull(text, "text");
    Charset charset = resolveCharset(encoding);
    // UTF-16's encoder always writes a BOM. Use an explicit byte order so the caller controls it.
    if ("UTF-16".equals(charset.name())) {
      charset = Charset.forName("UTF-16BE");
    }
    encoding = charset.name();
    String separator;
    if ("LF".equals(lineEnding)) separator = "\n";
    else if ("CRLF".equals(lineEnding)) separator = "\r\n";
    else if ("CR".equals(lineEnding)) separator = "\r";
    else throw new IOException("Unsupported line ending: " + lineEnding);
    if (bom
        && !"UTF-8".equals(encoding)
        && !"UTF-16LE".equals(encoding)
        && !"UTF-16BE".equals(encoding)) {
      throw new IOException(encoding + " does not support a byte-order mark.");
    }
    String normalized = normalize(text).replace("\n", separator);
    byte[] body;
    try {
      ByteBuffer encoded =
          charset
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(normalized));
      body = new byte[encoded.remaining()];
      encoded.get(body);
    } catch (CharacterCodingException exception) {
      throw new IOException(
          "Some characters cannot be saved as " + encoding + ". Choose UTF-8 to preserve them.",
          exception);
    }
    if (!bom) return body;
    byte[] prefix =
        "UTF-8".equals(encoding)
            ? new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf}
            : "UTF-16LE".equals(encoding)
                ? new byte[] {(byte) 0xff, (byte) 0xfe}
                : new byte[] {(byte) 0xfe, (byte) 0xff};
    byte[] result = Arrays.copyOf(prefix, prefix.length + body.length);
    System.arraycopy(body, 0, result, prefix.length, body.length);
    return result;
  }

  private static Charset resolveCharset(String encoding) throws IOException {
    if (encoding == null) throw new IOException("Choose a text encoding.");
    try {
      Charset charset = Charset.forName(encoding);
      String compact = charset.name().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
      if (!charset.canEncode() || compact.contains("UTF32") || compact.contains("BOM")) {
        throw new IOException("Unsupported encoding: " + encoding);
      }
      return charset;
    } catch (IllegalArgumentException exception) {
      throw new IOException("Unsupported encoding: " + encoding, exception);
    }
  }

  private static String decodeStrict(byte[] bytes, int offset, String encoding)
      throws CharacterCodingException {
    return Charset.forName(encoding)
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
        .toString();
  }

  private static boolean startsWith(byte[] bytes, int... prefix) {
    if (bytes.length < prefix.length) return false;
    for (int i = 0; i < prefix.length; i++) {
      if ((bytes[i] & 0xff) != prefix[i]) return false;
    }
    return true;
  }

  private static String guessUtf16(byte[] bytes) {
    if (bytes.length < 2 || (bytes.length & 1) != 0) return null;
    int pairs = Math.min(bytes.length / 2, 1024);
    int evenZeros = 0;
    int oddZeros = 0;
    for (int i = 0; i < pairs * 2; i += 2) {
      if (bytes[i] == 0) evenZeros++;
      if (bytes[i + 1] == 0) oddZeros++;
    }
    if (oddZeros > pairs / 3 && evenZeros == 0) return "UTF-16LE";
    if (evenZeros > pairs / 3 && oddZeros == 0) return "UTF-16BE";
    return null;
  }

  private static void rejectBinary(String text) throws IOException {
    int controls = 0;
    for (int i = 0; i < text.length(); i++) {
      char value = text.charAt(i);
      if (value == 0) throw new IOException("This appears to be a binary file (NUL characters).");
      if ((value < 0x20 && value != '\t' && value != '\n' && value != '\r' && value != '\f')
          || value == 0x7f) controls++;
    }
    if (controls > 0 && (text.length() < 100 || controls * 100L > text.length())) {
      throw new IOException("This appears to be a binary file (non-text control characters).");
    }
  }

  private static String normalize(String text) {
    return text.replace("\r\n", "\n").replace('\r', '\n');
  }

  private static String detectLineEnding(String text) {
    int lf = 0;
    int crlf = 0;
    int cr = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\r') {
        if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          crlf++;
          i++;
        } else cr++;
      } else if (text.charAt(i) == '\n') lf++;
    }
    if (crlf > 0 && crlf >= lf && crlf >= cr) return "CRLF";
    if (cr > lf && cr > crlf) return "CR";
    return "LF";
  }
}
