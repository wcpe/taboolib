package taboolib.platform;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import org.bukkit.Server;
import org.bukkit.entity.Entity;

/** 仅由 Folia 后端调用；编译时不加载旧 Bukkit 接口。 */
final class FoliaSchedulerAccess {

    private FoliaSchedulerAccess() {
    }

    static GlobalRegionScheduler global(Server server) {
        return server.getGlobalRegionScheduler();
    }

    static AsyncScheduler async(Server server) {
        return server.getAsyncScheduler();
    }

    static RegionScheduler region(Server server) {
        return server.getRegionScheduler();
    }

    static EntityScheduler entity(Entity entity) {
        return entity.getScheduler();
    }
}
