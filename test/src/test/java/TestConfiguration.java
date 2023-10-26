import de.exlll.configlib.Configuration;
import de.exlll.configlib.Path;

@Configuration
public class TestConfiguration extends TestAbstractClass {

    @Path("config.test")
    public int testValue = 0;

    @Path("config.test2.hello2")
    public TestValue testValue3 = new TestValue("test", new SubTestValue("test2"));

    @Path("config.test2.hello")
    public TestValue testValue2 = new TestValue("test", new SubTestValue("test2"));


    public record TestValue(String test, SubTestValue subTestValue) {
    }

    public record SubTestValue(String test) {
    }
}
