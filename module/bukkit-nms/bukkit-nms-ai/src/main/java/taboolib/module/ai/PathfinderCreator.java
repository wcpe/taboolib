package taboolib.module.ai;

/**
 * @author sky
 * @since 2018-09-21 13:06
 */
public interface PathfinderCreator {

    Object createPathfinderGoal(SimpleAi ai);

    /**
     * 获取当前 Goal 绑定的 SimpleAi。
     */
    SimpleAi getSimpleAi();

}
