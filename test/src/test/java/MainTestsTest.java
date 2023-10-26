import de.exlll.configlib.YamlConfigurationProperties;
import de.exlll.configlib.YamlConfigurations;
import org.junit.jupiter.api.Test;

import java.io.File;

class MainTestsTest {

    @Test
    void a() {
        YamlConfigurationProperties properties = YamlConfigurationProperties.newBuilder().build();

        File folder = new File(System.getProperty("user.dir"));

        File file = new File(folder, "test.yml");

        TestConfiguration alexConfiguration = YamlConfigurations.update(file.toPath(), TestConfiguration.class, properties);

        System.out.println(alexConfiguration.testValue);
        System.out.println(alexConfiguration.testValue2.subTestValue().test());

    }
}