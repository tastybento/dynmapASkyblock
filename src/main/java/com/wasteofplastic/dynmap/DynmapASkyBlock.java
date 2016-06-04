package com.wasteofplastic.dynmap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;

import com.wasteofplastic.askyblock.ASkyBlockAPI;
import com.wasteofplastic.askyblock.Island;


public class DynmapASkyBlock extends JavaPlugin {
    private static Logger log;
    private static final String DEF_INFOWINDOW = "<div class=\"infowindow\">Owner <span style=\"font-weight:bold;\">%ownername%</span> Level <span style=\"font-weight:bold;\">%islandlevel%</span><br /></div>";
    Plugin dynmap;
    DynmapAPI api;
    MarkerAPI markerapi;
    ASkyBlockAPI islandApi;
    int updatesPerTick = 20;

    FileConfiguration cfg;
    MarkerSet set;
    long updperiod;
    boolean use3d;
    String infowindow;
    AreaStyle defstyle;
    Map<String, AreaStyle> cusstyle;
    Map<String, AreaStyle> cuswildstyle;
    Map<String, AreaStyle> ownerstyle;
    Set<String> visible;
    Set<String> hidden;
    boolean stop; 
    int maxdepth;

    @Override
    public void onLoad() {
        log = this.getLogger();
    }

    private static class AreaStyle {
        String strokecolor;
        String unownedstrokecolor;
        double strokeopacity;
        int strokeweight;
        String fillcolor;
        double fillopacity;
        String label;

        AreaStyle(FileConfiguration cfg, String path, AreaStyle def) {
            strokecolor = cfg.getString(path+".strokeColor", def.strokecolor);
            unownedstrokecolor = cfg.getString(path+".unownedStrokeColor", def.unownedstrokecolor);
            strokeopacity = cfg.getDouble(path+".strokeOpacity", def.strokeopacity);
            strokeweight = cfg.getInt(path+".strokeWeight", def.strokeweight);
            fillcolor = cfg.getString(path+".fillColor", def.fillcolor);
            fillopacity = cfg.getDouble(path+".fillOpacity", def.fillopacity);
            label = cfg.getString(path+".label", null);
        }

        AreaStyle(FileConfiguration cfg, String path) {
            strokecolor = cfg.getString(path+".strokeColor", "#FF0000");
            unownedstrokecolor = cfg.getString(path+".unownedStrokeColor", "#00FF00");
            strokeopacity = cfg.getDouble(path+".strokeOpacity", 0.8);
            strokeweight = cfg.getInt(path+".strokeWeight", 3);
            fillcolor = cfg.getString(path+".fillColor", "#FF0000");
            fillopacity = cfg.getDouble(path+".fillOpacity", 0.35);
        }
    }

    public static void info(String msg) {
        log.log(Level.INFO, msg);
    }
    public static void severe(String msg) {
        log.log(Level.SEVERE, msg);
    }

    private Map<String, AreaMarker> resareas = new HashMap<String, AreaMarker>();

    private String formatInfoWindow(Island island, AreaMarker m) {
        String v = "<div class=\"islandinfo\">"+infowindow+"</div>";
        if (island.isSpawn() || island.getOwner() == null) {
            return "";
        }   
        v = v.replace("%owneruuid%", m.getLabel());
        OfflinePlayer owner = getServer().getOfflinePlayer(island.getOwner());
        String ownerName = owner.getName();
        if (ownerName == null) {
            ownerName = "Unknown";
        }
        v = v.replace("%ownername%", ownerName);
        v = v.replace("%islandlevel%", String.valueOf(islandApi.getIslandLevel(island.getOwner())));
        Set<UUID> memberSet = new HashSet<UUID>();
        String members = "";
        for (UUID member : island.getMembers()) {
            // Prevent duplicates
            if (!memberSet.contains(member)) {
                memberSet.add(member);
                OfflinePlayer memberPlayer = getServer().getOfflinePlayer(member);
                String memberName = memberPlayer.getName();
                if (!members.isEmpty()) {
                    members += ", ";
                }
                members += memberName;
            }
        }
        v = v.replace("%teammembers%", members);
        return v;
    }

    private boolean isVisible(String id, String worldname) {
        if((visible != null) && (visible.size() > 0)) {
            if((visible.contains(id) == false) && (visible.contains("world:" + worldname) == false) &&
                    (visible.contains(worldname + "/" + id) == false)) {
                return false;
            }
        }
        if((hidden != null) && (hidden.size() > 0)) {
            if(hidden.contains(id) || hidden.contains("world:" + worldname) || hidden.contains(worldname + "/" + id))
                return false;
        }
        return true;
    }

    /**
     * Adds a style
     * @param resid
     * @param worldid
     * @param m
     * @param island
     */
    private void addStyle(String resid, String worldid, AreaMarker m, Island island) {
        AreaStyle as = null;
        if(!ownerstyle.isEmpty()) {
            //info("DEBUG: ownerstyle is not empty " + getServer().getOfflinePlayer(island.getOwner()).getName());
            as = ownerstyle.get(getServer().getOfflinePlayer(island.getOwner()).getName());
            /*
	    if (as != null) {
		info("DEBUG: fill color = " + as.fillcolor);
		info("DEBUG: stroke color = " + as.strokecolor);
	    }*/
        }
        if(as == null) {
            //info("DEBUG: as = is null - using default style");
            as = defstyle;
        }
        int sc = 0xFF0000;
        int fc = 0xFF0000;
        try {
            sc = Integer.parseInt(as.strokecolor.substring(1), 16);
            fc = Integer.parseInt(as.fillcolor.substring(1), 16);
        } catch (NumberFormatException nfx) {
        }
        /*
	if (sc == 0xFF0000) {
	    info("DEBUG: stroke is red");
	} else {
	    info("DEBUG: stroke is not red");
	}*/
        m.setLineStyle(as.strokeweight, as.strokeopacity, sc);
        m.setFillStyle(as.fillopacity, fc);
        if(as.label != null) {
            m.setLabel(as.label);
        }
    }

    /* Handle specific islands */
    private void handleIsland(World world, Island island, Map<String, AreaMarker> newmap) {
        if (island.getOwner() == null) {
            return;
        }
        String name = island.getOwner().toString();
        double[] x = null;
        double[] z = null;

        /* Handle areas */
        if(isVisible(name, world.getName())) {
            /* Make outline */
            x = new double[4];
            z = new double[4];
            x[0] = island.getMinProtectedX(); z[0] = island.getMinProtectedZ();
            x[1] = island.getMinProtectedX(); z[1] = island.getMinProtectedZ() + island.getProtectionSize()+1.0;
            x[2] = island.getMinProtectedX() + island.getProtectionSize() + 1.0; z[2] = island.getMinProtectedZ() + island.getProtectionSize() +1.0;
            x[3] = island.getMinProtectedX() + island.getProtectionSize() + 1.0; z[3] = island.getMinProtectedZ();

            String markerid = world.getName() + "_" + name;
            AreaMarker m = resareas.remove(markerid); /* Existing area? */
            if(m == null) {
                m = set.createAreaMarker(markerid, name, false, world.getName(), x, z, false);
                if(m == null)
                    return;
            }
            else {
                m.setCornerLocations(x, z); /* Replace corner locations */
                m.setLabel(name);   /* Update label */
            }
            if(use3d) { /* If 3D? */
                m.setRangeY(world.getMaxHeight(), 0);
            }            
            /* Set line and fill properties */
            addStyle(name, world.getName(), m, island);

            /* Build popup */
            String desc = formatInfoWindow(island, m);

            m.setDescription(desc); /* Set popup */

            /* Add to map */
            newmap.put(markerid, m);
        }
    }

    private class UpdateJob implements Runnable {
        Map<String,AreaMarker> newmap = new HashMap<String,AreaMarker>(); /* Build new map */
        List<World> worldsToDo = null;
        List<Island> islandsToDo = null;
        World curworld = null;

        public void run() {
            if (stop) {
                return;
            }
            // If worlds list isn't primed, prime it
            if (worldsToDo == null) {
                worldsToDo = new ArrayList<World>();
                World islandWorld = islandApi.getIslandWorld();
                if (islandWorld == null) {
                    return;
                }
                worldsToDo.add(islandWorld);
                if (islandApi.isNewNether() && islandApi.getNetherWorld() != null) {
                    worldsToDo.add(islandApi.getNetherWorld());
                }
            }
            while (islandsToDo == null) {  // No pending islands for world
                if (worldsToDo.isEmpty()) { // No more worlds?
                    /* Now, review old map - anything left is gone */
                    for(AreaMarker oldm : resareas.values()) {
                        oldm.deleteMarker();
                    }
                    /* And replace with new map */
                    resareas = newmap;
                    // Set up for next update (new job)
                    getServer().getScheduler().scheduleSyncDelayedTask(DynmapASkyBlock.this, new UpdateJob(), updperiod);
                    return;
                } else {
                    // Get all the islands in this world
                    curworld = worldsToDo.remove(0);
                    islandsToDo = new ArrayList<Island>(islandApi.getOwnedIslands().values());      
                }
            }
            /* Now, process up to limit regions */
            for (int i = 0; i < updatesPerTick; i++) {
                if (islandsToDo.isEmpty()) {
                    islandsToDo = null;
                    break;
                }
                Island pr = islandsToDo.remove(islandsToDo.size()-1);
                handleIsland(curworld, pr, newmap);
            }
            // Tick next step in the job
            getServer().getScheduler().scheduleSyncDelayedTask(DynmapASkyBlock.this, this, 1);
        }
    }

    private class OurServerListener implements Listener {
        @EventHandler(priority=EventPriority.MONITOR)
        public void onPluginEnable(PluginEnableEvent event) {
            Plugin p = event.getPlugin();
            String name = p.getDescription().getName();
            if(name.equals("dynmap") || name.equals("ASkyBlock")) {
                if(dynmap.isEnabled())
                    activate();
            }
        }
    }

    public void onEnable() {
        info("initializing");
        PluginManager pm = getServer().getPluginManager();
        /* Get dynmap */
        dynmap = pm.getPlugin("dynmap");
        if(dynmap == null) {
            severe("Cannot find dynmap! Disabling plugin");
            pm.disablePlugin(this);
            return;
        }
        api = (DynmapAPI)dynmap; /* Get API */
        /* Get ASkyBlock */
        Plugin p = pm.getPlugin("ASkyBlock");
        if(p == null) {
            severe("Cannot find ASkyBlock! Disabling plugin");
            pm.disablePlugin(this);
            return;
        }
        saveDefaultConfig();

        /* If both enabled, activate */
        if(dynmap.isEnabled() && p.isEnabled()) {
            activate();
            getServer().getPluginManager().registerEvents(new OurServerListener(), this);        
        }
        /* Start up metrics */
        try {
            MetricsLite ml = new MetricsLite(this);
            ml.start();
        } catch (IOException iox) {

        }
    }

    private boolean reload = false;

    private void activate() {        
        /* Now, get markers API */
        markerapi = api.getMarkerAPI();
        if(markerapi == null) {
            severe("Error loading dynmap marker API!");
            return;
        }
        /* Load configuration */
        if(reload) {
            this.reloadConfig();
        }
        else {
            reload = true;
        }
        FileConfiguration cfg = getConfig();
        //cfg.options().copyDefaults(true);   /* Load defaults, if needed */
        //this.saveConfig();  /* Save updates, if needed */

        /* Now, add marker set for mobs (make it transient) */
        set = markerapi.getMarkerSet("askyblock.markerset");
        if(set == null)
            set = markerapi.createMarkerSet("askyblock.markerset", cfg.getString("layer.name", "ASkyBlock"), null, false);
        else
            set.setMarkerSetLabel(cfg.getString("layer.name", "ASkyBlock"));
        if(set == null) {
            severe("Error creating marker set");
            return;
        }
        int minzoom = cfg.getInt("layer.minzoom", 0);
        if(minzoom > 0)
            set.setMinZoom(minzoom);
        set.setLayerPriority(cfg.getInt("layer.layerprio", 10));
        set.setHideByDefault(cfg.getBoolean("layer.hidebydefault", false));
        use3d = cfg.getBoolean("use3dregions", false);
        infowindow = cfg.getString("infowindow", DEF_INFOWINDOW);
        maxdepth = cfg.getInt("maxdepth", 16);
        updatesPerTick = cfg.getInt("updates-per-tick", 20);

        /* Get style information */
        defstyle = new AreaStyle(cfg, "islandstyle");
        cusstyle = new HashMap<String, AreaStyle>();
        ownerstyle = new HashMap<String, AreaStyle>();
        cuswildstyle = new HashMap<String, AreaStyle>();
        ConfigurationSection sect = cfg.getConfigurationSection("ownerstyle");
        if(sect != null) {
            Set<String> ids = sect.getKeys(false);

            for(String id : ids) {
                ownerstyle.put(id, new AreaStyle(cfg, "ownerstyle." + id, defstyle));
            }
        }
        List<String> vis = cfg.getStringList("visibleislands");
        if(vis != null) {
            visible = new HashSet<String>(vis);
        }
        List<String> hid = cfg.getStringList("hiddenislands");
        if(hid != null) {
            hidden = new HashSet<String>(hid);
        }

        /* Set up update job - based on periond */
        int per = cfg.getInt("update.period", 300);
        if(per < 15) per = 15;
        updperiod = (long)(per*20);
        stop = false;
        islandApi = ASkyBlockAPI.getInstance();
        getServer().getScheduler().scheduleSyncDelayedTask(this, new UpdateJob(), 40);   /* First time is 2 seconds */

        info("version " + this.getDescription().getVersion() + " is activated");
    }

    public void onDisable() {
        if(set != null) {
            set.deleteMarkerSet();
            set = null;
        }
        resareas.clear();
        stop = true;
    }

}
