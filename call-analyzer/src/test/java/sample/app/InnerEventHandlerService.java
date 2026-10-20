package sample.app;

import java.util.Optional;

public class InnerEventHandlerService implements IObserver<A_DomainEvent> {

    private final IRepositoryForInnerEventFlow repositoryForInnerEventFlow;

    public InnerEventHandlerService(
            IRepositoryForInnerEventFlow repositoryForInnerEventFlow,
            DomainEventDispatcher domainEventDispatcher) {
        this.repositoryForInnerEventFlow = repositoryForInnerEventFlow;
        domainEventDispatcher.addObserver(this);
    }


    @Override
    public void handle(A_DomainEvent event) {
        String s = repositoryForInnerEventFlow.get();
        repositoryForInnerEventFlow.save(s+"1");
    }
}
