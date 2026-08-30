package juloo.keyboard2;

import juloo.keyboard2.KeyModifier;
import juloo.keyboard2.KeyValue;
import org.junit.Test;
import static juloo.keyboard2.TestUtils.*;
import static org.junit.Assert.*;

public class KeyModifierTest
{
  public KeyModifierTest() {}

  @Test
  public void compose() throws Exception
  {
    assertEquals(eval("compose", "space", "space"), key("nbsp"));
    assertEquals(eval("compose", "-", "space"), str("~"));
    assertEquals(eval("compose", "space", "-"), str("~"));
  }

  @Test
  public void hangul_shift() throws Exception
  {
    assertEquals(KeyModifier.modify(key("ㄱ"), KeyValue.Modifier.SHIFT), key("ㄲ"));
    assertEquals(KeyModifier.modify(key("ㅂ"), KeyValue.Modifier.SHIFT), key("ㅃ"));
    assertEquals(KeyModifier.modify(key("ㅐ"), KeyValue.Modifier.SHIFT), key("ㅒ"));
  }
}
