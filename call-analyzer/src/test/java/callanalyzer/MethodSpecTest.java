package callanalyzer;

import callanalyzer.analysis.MethodSpec;
import callanalyzer.model.MethodRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MethodSpecTest {

    private static List<String> resolve(String spec) {
        return MethodSpec.parse(spec).resolve(Fixtures.hierarchy()).stream().map(MethodRef::toString).toList();
    }

    @Test
    void acceptsTheSupportedForms() {
        List<String> expected = List.of("sample/cars/Mechanic.check(Lsample/cars/ICar;I)V");
        assertEquals(expected, resolve("sample.cars.Mechanic#check"));
        assertEquals(expected, resolve("sample.cars.Mechanic::check"));
        assertEquals(expected, resolve("sample.cars.Mechanic.check(ICar, int)"));
        assertEquals(expected, resolve("sample.cars.Mechanic#check(sample.cars.ICar car, int round)"));
        assertEquals(expected, resolve("sample.cars.Mechanic#check(Lsample/cars/ICar;I)V"));
        assertEquals(expected, resolve("sample/cars/Mechanic#check(Lsample/cars/ICar;I)"));
    }

    @Test
    void classOnlySelectsAllMethods() {
        assertEquals(List.of("sample/cars/Garage.<init>()V",
                        "sample/cars/Garage.describe(Ljava/lang/Object;)V",
                        "sample/cars/Garage.inspect(Lsample/cars/ICar;)V",
                        "sample/cars/Garage.lambda$serviceAll$0(Lsample/cars/ICar;)V",
                        "sample/cars/Garage.service(Lsample/cars/ICar;)V",
                        "sample/cars/Garage.serviceAll(Ljava/util/List;)V"),
                resolve("sample.cars.Garage"));
    }

    @Test
    void constructorBySimpleName() {
        assertEquals(List.of("sample/cars/Engine.<init>(Lsample/cars/ICar;)V"), resolve("sample.cars.Engine#Engine"));
        assertEquals(List.of("sample/cars/Engine.<init>(Lsample/cars/ICar;)V"), resolve("sample.cars.Engine#<init>"));
    }
}
