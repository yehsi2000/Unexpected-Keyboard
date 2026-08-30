package juloo.keyboard2;

import juloo.keyboard2.HangulComposer;
import juloo.keyboard2.HangulComposer.Result;
import org.junit.Test;
import static org.junit.Assert.*;

public class HangulComposerTest
{
  public HangulComposerTest() {}

  /** Feed every char of [s] to a fresh composer and return the final
      "commit + composing" concatenation, ignoring intermediate commits. */
  static String type(String s)
  {
    HangulComposer c = new HangulComposer();
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < s.length(); i++)
    {
      Result r = c.input(s.charAt(i));
      out.append(r.commit);
    }
    out.append(c.composing());
    return out.toString();
  }

  @Test
  public void basic_syllable() throws Exception
  {
    assertEquals("가나", type("ㄱㅏㄴㅏ"));
    assertEquals("한글", type("ㅎㅏㄴㄱㅡㄹ"));
  }

  @Test
  public void compound_final_split_on_vowel() throws Exception
  {
    HangulComposer c = new HangulComposer();
    Result r;
    r = c.input('ㄱ'); assertEquals("", r.commit); assertEquals("ㄱ", r.composing);
    r = c.input('ㅏ'); assertEquals("", r.commit); assertEquals("가", r.composing);
    r = c.input('ㅂ'); assertEquals("", r.commit); assertEquals("갑", r.composing);
    r = c.input('ㅅ'); assertEquals("", r.commit); assertEquals("값", r.composing);
    r = c.input('ㅣ'); assertEquals("갑", r.commit); assertEquals("시", r.composing);
  }

  @Test
  public void diphthongs() throws Exception
  {
    assertEquals("ㅘ", type("ㅗㅏ"));
    assertEquals("과", type("ㄱㅗㅏ"));
  }

  @Test
  public void no_initial_combining() throws Exception
  {
    HangulComposer c = new HangulComposer();
    Result r;
    r = c.input('ㄱ'); assertEquals("", r.commit); assertEquals("ㄱ", r.composing);
    r = c.input('ㄴ'); assertEquals("ㄱ", r.commit); assertEquals("ㄴ", r.composing);
  }

  @Test
  public void vowel_without_initial() throws Exception
  {
    HangulComposer c = new HangulComposer();
    Result r;
    r = c.input('ㅏ'); assertEquals("", r.commit); assertEquals("ㅏ", r.composing);
    r = c.input('ㄱ'); assertEquals("ㅏ", r.commit); assertEquals("ㄱ", r.composing);
  }

  @Test
  public void backspace_decomposes() throws Exception
  {
    HangulComposer c = new HangulComposer();
    c.input('ㄱ'); c.input('ㅏ'); c.input('ㅂ'); c.input('ㅅ');
    assertEquals("값", c.composing());
    assertEquals("갑", c.backspace().composing);
    assertEquals("가", c.backspace().composing);
    assertEquals("ㄱ", c.backspace().composing);
    assertEquals("", c.backspace().composing);
    assertNull(c.backspace());
  }
}
