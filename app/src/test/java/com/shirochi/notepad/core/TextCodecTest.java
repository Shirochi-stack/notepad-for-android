package com.shirochi.notepad.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;

public class TextCodecTest {
  @Test
  public void utf8RoundTripIncludesSupplementaryCharacters() throws IOException {
    String original = "Hello \uD83D\uDE00\nمرحبا\n日本語";
    byte[] encoded = TextCodec.encode(original, "UTF-8", "LF", false);
    TextCodec.Decoded decoded = TextCodec.decode(encoded);
    assertEquals(original, decoded.text);
    assertEquals("UTF-8", decoded.encoding);
    assertEquals("LF", decoded.lineEnding);
    assertFalse(decoded.bom);
  }

  @Test
  public void unicodeEncodingsBomAndLineEndingsRoundTrip() throws IOException {
    for (String encoding : new String[] {"UTF-8", "UTF-16LE", "UTF-16BE"}) {
      for (String lineEnding : new String[] {"LF", "CRLF", "CR"}) {
        byte[] bytes = TextCodec.encode("abc\n日本語\n", encoding, lineEnding, true);
        TextCodec.Decoded decoded = TextCodec.decode(bytes);
        assertEquals("abc\n日本語\n", decoded.text);
        assertEquals(encoding, decoded.encoding);
        assertEquals(lineEnding, decoded.lineEnding);
        assertTrue(decoded.bom);
        assertArrayEquals(
            bytes,
            TextCodec.encode(decoded.text, decoded.encoding, decoded.lineEnding, decoded.bom));
      }
    }
  }

  @Test
  public void detectsBomlessUtf16WithAlternatingNuls() throws IOException {
    for (String encoding : new String[] {"UTF-16LE", "UTF-16BE"}) {
      TextCodec.Decoded decoded =
          TextCodec.decode(TextCodec.encode("Hello\nWorld", encoding, "LF", false));
      assertEquals("Hello\nWorld", decoded.text);
      assertEquals(encoding, decoded.encoding);
      assertFalse(decoded.bom);
    }
  }

  @Test
  public void legacyWindows1252IsPreserved() throws IOException {
    byte[] bytes = new byte[] {'C', 'a', 'f', (byte) 0xe9, ' ', (byte) 0x80};
    TextCodec.Decoded decoded = TextCodec.decode(bytes);
    assertEquals("Café €", decoded.text);
    assertEquals("windows-1252", decoded.encoding);
    assertArrayEquals(
        bytes, TextCodec.encode(decoded.text, decoded.encoding, decoded.lineEnding, false));
  }

  @Test
  public void explicitLegacyEncodingsRoundTripInternationalText() throws IOException {
    String[][] samples = {
      {"Shift_JIS", "日本語のメモ"},
      {"EUC-JP", "日本語のメモ"},
      {"GB18030", "中文笔记 \uD83D\uDE00"},
      {"Big5", "中文筆記"},
      {"EUC-KR", "한국어 메모"},
      {"windows-1250", "Příliš žluťoučký"},
      {"windows-1251", "Русский текст"},
      {"windows-1253", "Ελληνικά"},
      {"windows-1254", "Türkçe ı"},
      {"windows-1255", "עברית"},
      {"windows-1256", "العربية"},
      {"windows-1257", "Latviešu"},
      {"windows-1258", "Viê\u0323t"},
      {"ISO-8859-15", "Café €"},
      {"KOI8-R", "Русский текст"},
      {"KOI8-U", "Українська"}
    };
    for (String[] sample : samples) {
      // Use the charset itself for fixtures; the editor must preserve externally encoded bytes.
      String original = sample[1] + "\r\nSecond line\r\n";
      byte[] bytes = original.getBytes(Charset.forName(sample[0]));
      TextCodec.Decoded decoded = TextCodec.decode(bytes, sample[0]);
      assertEquals(sample[0], sample[1] + "\nSecond line\n", decoded.text);
      assertEquals(Charset.forName(sample[0]).name(), decoded.encoding);
      assertEquals("CRLF", decoded.lineEnding);
      assertFalse(decoded.bom);
      assertArrayEquals(
          bytes, TextCodec.encode(decoded.text, decoded.encoding, decoded.lineEnding, decoded.bom));
    }
  }

  @Test
  public void chosenEncodingCanCorrectAutomaticLegacyInterpretation() throws IOException {
    byte[] bytes = "Привет".getBytes(Charset.forName("windows-1251"));
    assertEquals("windows-1252", TextCodec.decode(bytes).encoding);
    TextCodec.Decoded decoded = TextCodec.decode(bytes, "cp1251");
    assertEquals("Привет", decoded.text);
    assertEquals("windows-1251", decoded.encoding);
  }

  @Test
  public void explicitUnicodeAliasesPreserveBomAndEndianness() throws IOException {
    byte[] utf8 = TextCodec.encode("Café", "utf8", "LF", true);
    TextCodec.Decoded decoded = TextCodec.decode(utf8, "UTF8");
    assertEquals("Café", decoded.text);
    assertEquals("UTF-8", decoded.encoding);
    assertTrue(decoded.bom);
    for (String encoding : new String[] {"UTF-16LE", "UTF-16BE"}) {
      byte[] bytes = TextCodec.encode("日本語", encoding, "LF", true);
      decoded = TextCodec.decode(bytes, "UTF-16");
      assertEquals("日本語", decoded.text);
      assertEquals(encoding, decoded.encoding);
      assertTrue(decoded.bom);
      assertArrayEquals(bytes, TextCodec.encode(decoded.text, decoded.encoding, "LF", decoded.bom));
    }
  }

  @Test
  public void genericUtf16DoesNotSilentlyAddBom() throws IOException {
    assertArrayEquals(
        "Hello".getBytes(StandardCharsets.UTF_16BE),
        TextCodec.encode("Hello", "UTF-16", "LF", false));
    TextCodec.Decoded decoded = TextCodec.decode(TextCodec.encode("Hello", "UTF-16", "LF", true));
    assertEquals("Hello", decoded.text);
    assertEquals("UTF-16BE", decoded.encoding);
    assertTrue(decoded.bom);
  }

  @Test
  public void encodingChoicesUseCanonicalUniqueWritableNames() throws IOException {
    List<String> encodings = TextCodec.availableEncodings();
    assertEquals("UTF-8", encodings.get(0));
    assertEquals(encodings.size(), new HashSet<>(encodings).size());
    for (String encoding :
        new String[] {
          "UTF-16LE",
          "UTF-16BE",
          "windows-1252",
          "Shift_JIS",
          "GB18030",
          "Big5",
          "EUC-KR",
          "KOI8-R",
          "KOI8-U"
        }) {
      assertTrue(encoding, encodings.contains(Charset.forName(encoding).name()));
    }
    for (String encoding : encodings) {
      assertEquals(encoding, Charset.forName(encoding).name());
      assertTrue(encoding, Charset.forName(encoding).canEncode());
      assertNotNull(TextCodec.encode("", encoding, "LF", false));
    }
    assertFalse(encodings.contains("UTF-16"));
    assertFalse(encodings.contains("UTF-32"));
  }

  @Test(expected = IOException.class)
  public void selectedUtf8DoesNotFallbackOnInvalidData() throws IOException {
    TextCodec.decode(new byte[] {(byte) 0xe9}, "UTF-8");
  }

  @Test(expected = IOException.class)
  public void truncatedShiftJisIsRejected() throws IOException {
    TextCodec.decode(new byte[] {(byte) 0x82}, "Shift_JIS");
  }

  @Test(expected = IOException.class)
  public void truncatedGb18030IsRejected() throws IOException {
    TextCodec.decode(new byte[] {(byte) 0x81, 0x30, (byte) 0x81}, "GB18030");
  }

  @Test(expected = IOException.class)
  public void explicitEncodingKeepsBinaryProtection() throws IOException {
    TextCodec.decode(new byte[] {'a', 0, 'b'}, "Shift_JIS");
  }

  @Test(expected = IOException.class)
  public void selectedUtf16MustMatchBom() throws IOException {
    TextCodec.decode(TextCodec.encode("Hello", "UTF-16LE", "LF", true), "UTF-16BE");
  }

  @Test(expected = IOException.class)
  public void legacyCharsetCannotHaveBom() throws IOException {
    TextCodec.encode("日本語", "Shift_JIS", "LF", true);
  }

  @Test(expected = IOException.class)
  public void savingLegacyTextDoesNotReplaceUnmappableCharacters() throws IOException {
    TextCodec.encode("Emoji \uD83D\uDE00", "Shift_JIS", "LF", false);
  }

  @Test(expected = IOException.class)
  public void unsupportedDecodeCharsetProducesIoException() throws IOException {
    TextCodec.decode(new byte[] {'a'}, "not-a-real-charset");
  }

  @Test(expected = IOException.class)
  public void invalidEncodeCharsetProducesIoException() throws IOException {
    TextCodec.encode("text", "invalid charset!", "LF", false);
  }

  @Test
  public void mixedLineEndingsNormalizeToDominantStyle() throws IOException {
    TextCodec.Decoded decoded =
        TextCodec.decode("a\r\nb\nc\r\nd\r".getBytes(StandardCharsets.UTF_8));
    assertEquals("a\nb\nc\nd\n", decoded.text);
    assertEquals("CRLF", decoded.lineEnding);
    assertEquals(
        "a\r\nb\r\nc\r\nd\r\n",
        new String(
            TextCodec.encode(decoded.text, "UTF-8", decoded.lineEnding, false),
            StandardCharsets.UTF_8));
  }

  @Test
  public void encodeNormalizesInputWithoutDoublingCarriageReturns() throws IOException {
    assertEquals(
        "a\r\nb\r\nc\r\n",
        new String(
            TextCodec.encode("a\r\nb\rc\n", "UTF-8", "CRLF", false), StandardCharsets.UTF_8));
  }

  @Test
  public void emptyFileAndBomOnlyFileAreSupported() throws IOException {
    assertEquals("", TextCodec.decode(new byte[0]).text);
    TextCodec.Decoded decoded =
        TextCodec.decode(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf});
    assertEquals("", decoded.text);
    assertTrue(decoded.bom);
  }

  @Test(expected = IOException.class)
  public void invalidBomMarkedUtf8IsRejected() throws IOException {
    TextCodec.decode(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, (byte) 0xc3});
  }

  @Test(expected = IOException.class)
  public void truncatedUtf16IsRejected() throws IOException {
    TextCodec.decode(new byte[] {(byte) 0xff, (byte) 0xfe, 0x61});
  }

  @Test(expected = IOException.class)
  public void malformedUtf16SurrogateIsRejected() throws IOException {
    TextCodec.decode(new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, (byte) 0xd8});
  }

  @Test(expected = IOException.class)
  public void undefinedWindows1252ByteIsRejected() throws IOException {
    TextCodec.decode(new byte[] {(byte) 0x81});
  }

  @Test(expected = IOException.class)
  public void utf32IsRejectedRatherThanMisreadAsUtf16() throws IOException {
    TextCodec.decode(new byte[] {(byte) 0xff, (byte) 0xfe, 0, 0, 0x61, 0, 0, 0});
  }

  @Test(expected = IOException.class)
  public void binaryNulsAreRejected() throws IOException {
    TextCodec.decode(new byte[] {'a', 0, 'b'});
  }

  @Test(expected = IOException.class)
  public void binaryControlsAreRejected() throws IOException {
    TextCodec.decode(new byte[] {1, 2, 3, 4, 5});
  }

  @Test(expected = IOException.class)
  public void unencodableLegacyCharactersAreRejected() throws IOException {
    TextCodec.encode("日本語", "windows-1252", "LF", false);
  }

  @Test(expected = IOException.class)
  public void unpairedSurrogateIsRejectedOnSave() throws IOException {
    TextCodec.encode("\uD800", "UTF-8", "LF", false);
  }

  @Test(expected = IOException.class)
  public void legacyEncodingCannotHaveBom() throws IOException {
    TextCodec.encode("text", "windows-1252", "LF", true);
  }

  @Test(expected = IOException.class)
  public void unknownEncodingIsRejected() throws IOException {
    TextCodec.encode("text", "UTF-32", "LF", false);
  }
}
