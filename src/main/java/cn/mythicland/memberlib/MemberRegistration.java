package cn.mythicland.memberlib;

public interface MemberRegistration extends AutoCloseable {
    MemberDefinition definition();

    @Override
    void close();
}
