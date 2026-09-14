package cn.mythicland.mythicmembership.api;

public interface MembershipRegistration extends AutoCloseable {
    MembershipDefinition definition();

    @Override
    void close();
}
