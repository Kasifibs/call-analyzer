package sample.app;

import java.util.Optional;

public class ComplexService implements IComplexService{

    private final SimpleRepository simpleRepository;
    private final SimpleService2 simpleService2;
    private final IServiceInterface serviceInterface;
    private final EventEmitterService eventEmitterService;
    private final IComplexRepository complexRepository;

    public ComplexService(
            SimpleRepository simpleRepository,
            SimpleService2 simpleService2,
            IServiceInterface serviceInterface,
            EventEmitterService eventEmitterService,
            IComplexRepository complexRepository) {
        this.simpleRepository = simpleRepository;
        this.simpleService2 = simpleService2;
        this.serviceInterface = serviceInterface;
        this.eventEmitterService = eventEmitterService;
        this.complexRepository = complexRepository;
    }

    @Override
    public void handle() {
        String s = complexRepository.get();
        Optional<Integer> integer = Optional.of(1989);
        integer.ifPresent(integer1 -> {
            simpleRepository.get();
            serviceInterface.handle("r", 1);
        });
        simpleService2.handle();
        complexRepository.save(s + "asd");
        eventEmitterService.handle();
    }
}
