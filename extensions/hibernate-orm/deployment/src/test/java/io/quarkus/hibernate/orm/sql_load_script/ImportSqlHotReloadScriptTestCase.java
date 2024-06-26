package io.quarkus.hibernate.orm.sql_load_script;

import static org.hamcrest.Matchers.is;

import java.util.function.Function;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.hibernate.orm.MyEntity;
import io.quarkus.hibernate.orm.TestTags;
import io.quarkus.test.QuarkusDevModeTest;
import io.restassured.RestAssured;

@Tag(TestTags.DEVMODE)
public class ImportSqlHotReloadScriptTestCase {
    @RegisterExtension
    final static QuarkusDevModeTest TEST = new QuarkusDevModeTest()
            .withApplicationRoot((jar) -> jar
                    .addClasses(MyEntity.class, SqlLoadScriptTestResource.class)
                    .addAsResource("application-import-load-script-test.properties", "application.properties")
                    .addAsResource("import.sql"));

    @Test
    public void testImportSqlScriptHotReload() {
        String expectedName = "import.sql load script entity";
        assertBodyIs(expectedName);

        String hotReloadExpectedName = "modified import.sql load script entity";
        TEST.modifyResourceFile("import.sql", new Function<String, String>() {
            @Override
            public String apply(String s) {
                return s.replace(expectedName, hotReloadExpectedName);
            }
        });
        assertBodyIs(hotReloadExpectedName);
    }

    private void assertBodyIs(String expectedBody) {
        RestAssured.when().get("/orm-sql-load-script/2").then().body(is(expectedBody));
    }
}
