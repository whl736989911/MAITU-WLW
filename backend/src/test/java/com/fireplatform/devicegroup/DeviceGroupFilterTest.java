package com.fireplatform.devicegroup;

import com.fasterxml.jackson.databind.*;
import com.fireplatform.shared.*;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fireplatform.devicegroup.DeviceGroupModels.*;

class DeviceGroupFilterTest {
    static final String ID="123e4567-e89b-12d3-a456-426614174000";
    final ObjectMapper json=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    final DeviceGroupInputs inputs=new DeviceGroupInputs(json);
    byte[] raw(String value){return value.getBytes(StandardCharsets.UTF_8);}
    byte[] body(Map<String,Object> value)throws Exception{return json.writeValueAsBytes(value);}
    void bad(org.assertj.core.api.ThrowableAssert.ThrowingCallable code){assertThatThrownBy(code).isInstanceOfSatisfying(ApiError.class,e->{assertThat(e.status).isEqualTo(400);assertThat(e.code).isEqualTo("INVALID_REQUEST");});}
    void unconfirmed(org.assertj.core.api.ThrowableAssert.ThrowingCallable code){assertThatThrownBy(code).isInstanceOfSatisfying(ApiError.class,e->{assertThat(e.status).isEqualTo(503);assertThat(e.code).isEqualTo("DEVICE_GROUP_READ_UNCONFIRMED");assertThat(e.getMessage()).doesNotContain("SECRET");});}
    @Test void nameUsesExactJavaTrimNotJavascriptUnicodeTrim(){assertThat(DeviceGroupInputs.name("  Case Group  ")).isEqualTo("Case Group");assertThat(DeviceGroupInputs.name("\u00a0Case\u00a0")).isEqualTo("\u00a0Case\u00a0");assertThat(DeviceGroupInputs.name("\uFEFFCase\uFEFF")).isEqualTo("\uFEFFCase\uFEFF");assertThat(DeviceGroupInputs.name("case")).isNotEqualTo(DeviceGroupInputs.name("Case"));}
    @Test void controlsAreRejectedBeforeTrimAndBadUnicodeNeverRepaired(){for(String value:List.of(""," ","\tGroup","Group\n","\u0000Group","Group\u007f","\uD800"))bad(()->DeviceGroupInputs.name(value));bad(()->DeviceGroupInputs.name(null));}
    @Test void descriptionIsRequiredButEmptyAndInternalNewlineTabAreValid(){assertThat(DeviceGroupInputs.description(" \r\n\t ")).isEmpty();assertThat(DeviceGroupInputs.description(" a\n\tb ")).isEqualTo("a\n\tb");bad(()->DeviceGroupInputs.description(null));bad(()->DeviceGroupInputs.description("\u0000a"));bad(()->DeviceGroupInputs.description("\uD800"));}
    @Test void boundsAreUtf16UnitsAndUseOnlyCentralApprovedConstants(){assertThat(DeviceGroupInputs.name("😀".repeat(100))).hasSize(Limits.DEVICE_GROUP_NAME_MAX);bad(()->DeviceGroupInputs.name("😀".repeat(101)));assertThat(DeviceGroupInputs.description("😀".repeat(500))).hasSize(Limits.DEVICE_GROUP_DESCRIPTION_MAX);bad(()->DeviceGroupInputs.description("😀".repeat(501)));}
    @Test void opaqueDeviceIdsAreNotTrimmedUuidParsedOrPathEncoded(){for(String id:List.of("legacy/a%?#",".","..","  id  ","1-1-1-1-1",ID.toUpperCase(Locale.ROOT)))assertThat(DeviceGroupInputs.deviceId(id)).isEqualTo(id);for(String id:List.of("","x".repeat(65),"x\n","\uD800"))bad(()->DeviceGroupInputs.deviceId(id));}
    @Test void metadataKeysAreExactRequiredAndNeverCoerceScalarText()throws Exception{
        var values=new HashMap<String,Object>(Map.of("name"," Group ","description",""));assertThat(inputs.metadata(body(values),false).name()).isEqualTo("Group");
        for(String key:List.of("name","description")){var missing=new HashMap<>(values);missing.remove(key);bad(()->inputs.metadata(body(missing),false));var c=new HashMap<>(values);c.put(key,null);bad(()->inputs.metadata(body(c),false));}
        for(Object value:List.of(1,true,List.of("x"),Map.of("x","y"))){values.put("name",value);bad(()->inputs.metadata(body(values),false));}values.put("name","Group");values.put("tenant_id","tenant-b");bad(()->inputs.metadata(body(values),false));
    }
    @Test void jsonDuplicateTrailingScalarAndArrayInputsAreRejectedBeforeDomainWrite(){for(String text:List.of("{} {}","[]","null","1","{\"name\":\"a\",\"name\":\"b\",\"description\":\"\"}"))bad(()->inputs.metadata(raw(text),false));}
    @Test void expectedRevisionMustBeRawSafeJsonIntegerNotStringDecimalExponentOrBool(){
        for(String value:List.of("\"1\"","1.0","1e0","true","null","-1","9007199254740992","9223372036854775808"))bad(()->inputs.membership(raw("{\"device_id\":\"legacy/a\",\"expected_revision\":"+value+"}")));
        assertThat(inputs.membership(raw("{\"device_id\":\"legacy/a\",\"expected_revision\":9007199254740991}")).expectedRevision()).isEqualTo(DeviceGroupInputs.MAX_SAFE);
    }
    @Test void actorConfirmationPasswordIsNotAcceptedByGroupWrites()throws Exception{bad(()->inputs.metadata(body(Map.of("name","G","description","","actor_password","Synthetic-Confirm-Password")),false));bad(()->inputs.membership(raw("{\"device_id\":\"legacy/a\",\"expected_revision\":0,\"actor_password\":\"Synthetic-Confirm-Password\"}")));}
    @Test void groupIdUnlikeDeviceIdIsCompleteCanonicalLowercaseUuid(){assertThat(DeviceGroupProjection.groupId(ID)).isTrue();for(String id:List.of(ID.toUpperCase(Locale.ROOT),ID+"\n","1-1-1-1-1","legacy/a"))assertThat(DeviceGroupProjection.groupId(id)).isFalse();}
    @Test void keywordUsesExistingUnicodeTrimLiteralWildcardsAndMalformedTextPolicy(){var f=DeviceGroupFilter.parse(" \uFEFF%_!' OR 1=1 --\u00a0");var groups=f.groups("tenant-a");assertThat(groups.where()).contains("g.tenant_id=?","LOWER(g.name)","LOWER(g.description)").doesNotContain("OR 1=1");assertThat(groups.arguments()).containsExactly("tenant-a","%!%!_!!' OR 1=1 --%","%!%!_!!' OR 1=1 --%");for(String value:List.of("x".repeat(101),"\u0000","\uD800"))bad(()->DeviceGroupFilter.parse(value));}
    @Test void childFilterIsBoundedCurrentTenantAndUsesOnlyFourDeviceColumns(){var plan=DeviceGroupFilter.parse("q").devices("tenant-a",ID,false);assertThat(plan.arguments()).hasSize(6).startsWith("tenant-a",ID);assertThat(plan.where()).contains("m.tenant_id=? AND m.group_id=?","LOWER(d.id)","LOWER(d.name)","LOWER(d.device_eui)","LOWER(d.address)").doesNotContain("product","users","physical","battery");assertThat(DeviceGroupProjection.DEVICE_COLUMNS).isEqualTo("d.id AS device_id,d.name AS device_name,d.device_eui,d.address");}
    @Test void queryTokensAreCanonicalUnsignedIntegersWithoutOverflow(){assertThat(DeviceGroupFilter.number(null,1)).isEqualTo(1);for(String value:List.of("","01","+1","-1","1.0","1e0"," 1","１","2147483648"))bad(()->DeviceGroupFilter.number(value,null));}
    ResultSet group()throws Exception{var rs=mock(ResultSet.class);when(rs.getString("id")).thenReturn(ID);when(rs.getString("tenant_id")).thenReturn("tenant-a");when(rs.getString("name")).thenReturn("Group");when(rs.getString("description")).thenReturn("");return rs;}
    @Test void groupProjectionIsExactAndRejectsWrongTenantBadIdOrUnsafeNumbers()throws Exception{var rs=group();assertThat(DeviceGroupProjection.group(rs,"tenant-a").getClass().getRecordComponents()).hasSize(6);unconfirmed(()->DeviceGroupProjection.group(rs,"tenant-b"));when(rs.getString("id")).thenReturn("SECRET");unconfirmed(()->DeviceGroupProjection.group(rs,"tenant-a"));var r=group();when(r.getLong("revision")).thenReturn(DeviceGroupInputs.MAX_SAFE+1);unconfirmed(()->DeviceGroupProjection.group(r,"tenant-a"));}
    @Test void sourceMetadataIsNotSilentlyTrimmedRepairedOrNullFilled()throws Exception{for(String value:List.of(" Group ","","SECRET\n")){var rs=group();when(rs.getString("name")).thenReturn(value);unconfirmed(()->DeviceGroupProjection.group(rs,"tenant-a"));}var rs=group();when(rs.getString("description")).thenReturn(null);unconfirmed(()->DeviceGroupProjection.group(rs,"tenant-a"));}
    ResultSet device()throws Exception{var rs=mock(ResultSet.class);when(rs.getString("device_id")).thenReturn("legacy/a%?#");for(String field:List.of("device_name","device_eui","address"))when(rs.getString(field)).thenReturn("");return rs;}
    @Test void emptyLegacyReferenceMetadataIsPreservedWithoutNewDirectories()throws Exception{var value=DeviceGroupProjection.device(device());assertThat(value.deviceId()).isEqualTo("legacy/a%?#");assertThat(value.deviceName()).isEmpty();assertThat(value.deviceEui()).isEmpty();assertThat(value.address()).isEmpty();assertThat(value.getClass().getRecordComponents()).hasSize(4);}
    @Test void badReferenceShapeAndNullBooleanAreNotGuessedAsFalse()throws Exception{var rs=device();when(rs.getString("address")).thenReturn(null);unconfirmed(()->DeviceGroupProjection.device(rs));var flag=device();when(flag.wasNull()).thenReturn(true);unconfirmed(()->DeviceGroupProjection.candidate(flag));}
    @Test void countsMustBeNonNullNonnegativeAndJsonSafe()throws Exception{for(long n:new long[]{-1,Long.MIN_VALUE,DeviceGroupInputs.MAX_SAFE+1,Long.MAX_VALUE}){var rs=mock(ResultSet.class);when(rs.getLong(1)).thenReturn(n);unconfirmed(()->DeviceGroupProjection.total(rs));}var rs=mock(ResultSet.class);when(rs.wasNull()).thenReturn(true);unconfirmed(()->DeviceGroupProjection.total(rs));}
    @Test void storedGroupReplayUsesExactRawShapeWithoutLongOrStringCoercion()throws Exception{
        JsonNode good=json.readTree("{\"id\":\""+ID+"\",\"tenant_id\":\"tenant-a\",\"name\":\"Group\",\"description\":\"\",\"revision\":0,\"member_count\":0}");assertThat(DeviceGroupProjection.stored(good).id()).isEqualTo(ID);
        var bad=good.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)bad).put("revision","0");unconfirmed(()->DeviceGroupProjection.stored(bad));var extra=good.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)extra).put("SECRET",true);unconfirmed(()->DeviceGroupProjection.stored(extra));
        assertThat(json.readValue(json.writeValueAsBytes(DeviceGroupProjection.stored(good)),Group.class)).isEqualTo(DeviceGroupProjection.stored(good));var page=new MemberPage(DeviceGroupProjection.stored(good),List.of(),0,1,20);assertThat(json.readValue(json.writeValueAsBytes(page),MemberPage.class)).isEqualTo(page);
    }
    @Test void invalidCentralSettingFailsSafelyEvenWhenExplicitSizeWouldOtherwiseHideIt(){for(long value:new long[]{0,4,101,Long.MAX_VALUE})for(Integer size:Arrays.asList(null,1)){var jdbc=mock(JdbcTemplate.class);var sizes=mock(PageSizePolicy.class);when(sizes.defaultPageSize()).thenReturn(value);var reader=new DeviceGroupQueries(jdbc, sizes, mock(TransactionRequirements.class), new com.fireplatform.identity.DeviceScopes(jdbc), new com.fireplatform.shared.ScopeFilter(jdbc));unconfirmed(()->reader.groups("bootstrap-admin","tenant-a",1,size,DeviceGroupFilter.parse(null)));assertThat(mockingDetails(jdbc).getInvocations()).isEmpty();}}
    @Test void unsupportedSqlStatesOrOtherConstraintNamesNeverBecomeNameConflict(){for(Throwable failure:List.of(new java.sql.SQLException("uq_device_groups_tenant_name","23505"),new java.sql.SQLException("other unique","23505"),new java.sql.SQLException("constraint","23503"),new IllegalStateException("programmer error")))assertThat(DeviceGroupConflicts.name(failure)).isFalse();}
}
