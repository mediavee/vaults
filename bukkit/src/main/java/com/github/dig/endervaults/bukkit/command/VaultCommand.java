package com.github.dig.endervaults.bukkit.command;

import com.github.dig.endervaults.api.EnderVaultsPlugin;
import com.github.dig.endervaults.api.VaultPluginProvider;
import com.github.dig.endervaults.api.lang.Lang;
import com.github.dig.endervaults.api.lang.Language;
import com.github.dig.endervaults.api.permission.UserPermission;
import com.github.dig.endervaults.api.vault.Vault;
import com.github.dig.endervaults.api.vault.VaultRegistry;
import com.github.dig.endervaults.api.vault.metadata.VaultDefaultMetadata;
import com.github.dig.endervaults.bukkit.ui.selector.SelectorInventory;
import com.github.dig.endervaults.bukkit.vault.BukkitVault;
import com.github.dig.endervaults.bukkit.vault.BukkitVaultFactory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Optional;

public class VaultCommand implements CommandExecutor {

    private final EnderVaultsPlugin plugin = VaultPluginProvider.getPlugin();
    private final Language language = plugin.getLanguage();
    private final UserPermission<Player> permission = plugin.getPermission();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player) {
            Player player = (Player) sender;

            if (!permission.canUseVaultCommand(player)) {
                sender.sendMessage(language.get(Lang.NO_PERMISSION));
                return true;
            }

            if (!plugin.getPersister().isLoaded(player.getUniqueId())) {
                sender.sendMessage(language.get(Lang.PLAYER_NOT_LOADED));
                return true;
            }

            if (args.length == 1) {
                VaultRegistry registry = plugin.getRegistry();

                int orderValue;
                try {
                    orderValue = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(language.get(Lang.INVALID_VAULT_ORDER));
                    return true;
                }

                if (orderValue <= 0) {
                    sender.sendMessage(language.get(Lang.INVALID_VAULT_ORDER));
                    return true;
                }

                boolean canUseVault = permission.canUseVault(player, orderValue);

                Optional<Vault> vaultOptional = registry
                        .getByMetadata(player.getUniqueId(), VaultDefaultMetadata.ORDER.getKey(), orderValue);

                BukkitVault vault;
                if (vaultOptional.isPresent()) {
                    vault = (BukkitVault) vaultOptional.get();
                } else {
                    if (!canUseVault) {
                        sender.sendMessage(language.get(Lang.NO_PERMISSION));
                        return true;
                    }

                    vault = (BukkitVault) BukkitVaultFactory.create(player.getUniqueId(), new HashMap<String, Object>(){{
                        put(VaultDefaultMetadata.ORDER.getKey(), orderValue);
                    }});
                    registry.register(player.getUniqueId(), vault);
                }

                vault.launchFor(player);
            } else {
                new SelectorInventory(player.getUniqueId(), 1).launchFor(player);
            }
        }
        return true;
    }
}
