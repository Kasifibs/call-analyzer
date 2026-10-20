package sample.app;

import java.util.Optional;

public class InnerEventHandlerService2 implements IObserver<B_DomainEvent> {

    private final RepositoryForInnerEventFlow2 repositoryForInnerEventFlow2;

    public InnerEventHandlerService2(
            RepositoryForInnerEventFlow2 repositoryForInnerEventFlow2,
            DomainEventDispatcher domainEventDispatcher) {
        this.repositoryForInnerEventFlow2 = repositoryForInnerEventFlow2;
        domainEventDispatcher.addObserver(this);
    }


    @Override
    public void handle(B_DomainEvent event) {
        String s = repositoryForInnerEventFlow2.get();
        repositoryForInnerEventFlow2.save(s+"1");
    }
}
