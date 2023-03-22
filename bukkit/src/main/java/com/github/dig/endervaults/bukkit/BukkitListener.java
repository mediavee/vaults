package com.github.dig.endervaults.bukkit;

import com.github.dig.endervaults.api.VaultPluginProvider;
import com.github.dig.endervaults.api.lang.Lang;
import com.github.dig.endervaults.api.permission.UserPermission;
import com.github.dig.endervaults.api.vault.Vault;
import com.github.dig.endervaults.api.vault.VaultPersister;
import com.github.dig.endervaults.api.vault.metadata.VaultDefaultMetadata;
import com.github.dig.endervaults.bukkit.ui.selector.SelectorInventory;
import com.github.dig.endervaults.bukkit.vault.BukkitVaultRegistry;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import it.unimi.dsi.fastutil.Hash;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

public class BukkitListener implements Listener {

    private final EVBukkitPlugin plugin = (EVBukkitPlugin) VaultPluginProvider.getPlugin();
    private final VaultPersister persister = plugin.getPersister();
    private final UserPermission<Player> permission = plugin.getPermission();

    private final Map<UUID, BukkitTask> pendingLoadMap = new HashMap<>();

    private final Supplier<HashMap<Material, Integer>> blacklistedMaterials = Suppliers.memoize(() -> {
        FileConfiguration configuration = plugin.getConfigFile().getConfiguration();
        return configuration.getStringList("vault.blacklist.items")
                .stream()
                .collect(Collectors.toMap(
                        s -> s.contains(":") ? Material.getMaterial(s.split(":")[0]) : Material.getMaterial(s),
                        s -> s.contains(":") ? Integer.parseInt(s.split(":")[1]) : -1,
                        (a, b) -> a,
                        HashMap::new));
    });

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        FileConfiguration config = plugin.getConfigFile().getConfiguration();
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin,
                () -> persister.load(player.getUniqueId()),
                config.getLong("storage.settings.load-delay", 5 * 20));
        pendingLoadMap.put(player.getUniqueId(), bukkitTask);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (pendingLoadMap.containsKey(player.getUniqueId())) {
            pendingLoadMap.remove(player.getUniqueId()).cancel();
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> persister.save(player.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();

        BukkitVaultRegistry registry = (BukkitVaultRegistry) plugin.getRegistry();
        Inventory inventory = event.getInventory();

        Optional<Vault> optionalVault = registry.getVault(player, inventory);
        if (!optionalVault.isPresent())
            return;

        for (ItemStack item : Arrays.asList(
                event.getCurrentItem(),
                event.getCursor(),
                (event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY) ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton()) : event.getCurrentItem())) {

            if (item != null) {
                if (isItemBlacklisted(player, item)) {
                    player.sendMessage(plugin.getLanguage().get(Lang.BLACKLISTED_ITEM));
                    event.setCancelled(true);
                    return;
                }
                else {
                    int order = (int) optionalVault.get().getMetadata().get(VaultDefaultMetadata.ORDER.getKey());
                    if (!permission.canUseVault(player, order)) {
                        player.sendMessage(plugin.getLanguage().get(Lang.CANNOT_EDIT));
                        event.setCancelled(true);
                        return;
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(InventoryMoveItemEvent event) {
        BukkitVaultRegistry registry = (BukkitVaultRegistry) plugin.getRegistry();
        ItemStack item = event.getItem();
        Inventory inventory = event.getDestination();

        if (inventory != null && item != null && isBlacklistEnabled()) {
            if (isItemBlacklisted(item) && registry.isVault(inventory)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        Player player = (Player) event.getWhoClicked();

        BukkitVaultRegistry registry = (BukkitVaultRegistry) plugin.getRegistry();
        ItemStack item = event.getCursor();
        Inventory inventory = event.getInventory();

        Optional<Vault> optionalVault = registry.getVault(player, inventory);
        if (!optionalVault.isPresent())
            return;

        if (item != null) {
            if (isItemBlacklisted(player, item)) {
                player.sendMessage(plugin.getLanguage().get(Lang.BLACKLISTED_ITEM));
                event.setCancelled(true);
            } else {
                int order = (int) optionalVault.get().getMetadata().get(VaultDefaultMetadata.ORDER.getKey());
                if (!permission.canUseVault(player, order)) {
                    player.sendMessage(plugin.getLanguage().get(Lang.CANNOT_EDIT));
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && block.getType() == Material.ENDER_CHEST && isEnderchestReplaced()) {
            event.setCancelled(true);
            if (!persister.isLoaded(player.getUniqueId())) {
                player.sendMessage(plugin.getLanguage().get(Lang.PLAYER_NOT_LOADED));
                return;
            }
            new SelectorInventory(player.getUniqueId(), 1).launchFor(player);
        }
    }

    private boolean isEnderchestReplaced() {
        FileConfiguration configuration = plugin.getConfigFile().getConfiguration();
        return configuration.getBoolean("enderchest.replace-with-selector", false);
    }

    private boolean isBlacklistEnabled() {
        FileConfiguration configuration = plugin.getConfigFile().getConfiguration();
        return configuration.getBoolean("vault.blacklist.enabled", false);
    }

    public boolean isItemBlacklisted(ItemStack item) {
        if (isBlacklistEnabled() && getBlacklisted().containsKey(item.getType())) {
            Integer data = getBlacklisted().get(item.getType());
            if (data < 0 || data == item.getDurability()) {
                return true;
            }
        }
        return false;
    }

    public boolean isItemBlacklisted(Player player, ItemStack item) {
        return isItemBlacklisted(item) && !permission.canBypassBlacklist(player);
    }

    public HashMap<Material, Integer> getBlacklisted() {
        return blacklistedMaterials.get();
    }
}
