package cloud.tobbbles.gielinor.runelite;

import cloud.tobbbles.gielinor.runelite.v1.CharacterRef;
import cloud.tobbbles.gielinor.runelite.v1.CharacterState;
import cloud.tobbbles.gielinor.runelite.v1.PushRequest;
import cloud.tobbbles.gielinor.runelite.v1.Skill;
import com.google.protobuf.FieldMask;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * The generated (lite) classes work on the protobuf runtime RuneLite's client ships, and carry
 * the mask paths the relay reads: map-entry paths such as `skills.ATTACK` are why the plugin
 * speaks binary protobuf rather than JSON.
 */
public class WireContractTest
{
	@Test
	public void pushRequestRoundTripsThroughBinary() throws Exception
	{
		PushRequest push = PushRequest.newBuilder()
			.setClientId("install-1")
			.setCharacter(CharacterRef.newBuilder()
				.setAccountHash(123456789L)
				.setRsn("Zezima")
				.setLoggedIn(true)
				.setProfile("STANDARD"))
			.setSeq(1_760_000_000_000L)
			.setState(CharacterState.newBuilder()
				.putSkills("ATTACK", Skill.newBuilder().setLevel(70).setXp(737_627).build()))
			.setUpdateMask(FieldMask.newBuilder().addPaths("skills.ATTACK").addPaths("bank"))
			.build();

		PushRequest decoded = PushRequest.parseFrom(push.toByteArray());

		assertEquals(push, decoded);
		assertEquals(70, decoded.getState().getSkillsOrThrow("ATTACK").getLevel());
		assertEquals("skills.ATTACK", decoded.getUpdateMask().getPaths(0));
	}
}
