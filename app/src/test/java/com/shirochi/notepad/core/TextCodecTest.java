package com.shirochi.notepad.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
