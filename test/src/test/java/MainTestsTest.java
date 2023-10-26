import de.exlll.configlib.YamlConfigurationProperties;
import de.exlll.configlib.YamlConfigurations;
import org.junit.jupiter.api.Test;

import java.io.File;

class MainTestsTest {

    @Test
    void a() {
        YamlConfigurationProperties properties = YamlConfigurationProperties.newBuilder().build();

        File folder = new File("C:\\Users\\Alessandro\\Desktop\\Programmazione\\Java\\Plugins\\ConfigLib\\configlib-core\\build\\tmp\\test");


        File file = new File(folder, "alex.yml");

        System.out.println(file.toPath());

        AlexTestConfiguration alexConfiguration = YamlConfigurations.update(file.toPath(), AlexTestConfiguration.class, properties);

        System.out.println(alexConfiguration.testValue);
        System.out.println(alexConfiguration.testValue2.subTestValue().test());

    }
}