package dev.donutshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionType;

import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.*;

public class ShopPlugin extends JavaPlugin implements Listener {

    record Currency(String id, String type, String symbol, String label) {}
    record Entry(Material material, String name, int price, int amount,
                 Map<String, Integer> enchants, String command, String potion) {}

    static class Tooltip {
        String tag = "Description";
        String infoLabel = "Information:";
        String clickText = "to view";
        boolean clickColored = false;
        boolean showCurrency = true;
        boolean hideAttributes = true;
        List<String> info = new ArrayList<>();
        List<String> extra = new ArrayList<>();
    }

    static class Category {
        String id, title, parent;
        TextColor color;
        Material icon;
        int slot, rows;
        boolean main, sign;
        Currency currency;
        Tooltip tip = new Tooltip();
        int[] fill;
        List<String> children = new ArrayList<>();
        List<Entry> items = new ArrayList<>();
    }

    static class ShopHolder implements InventoryHolder {
        final Category category;                 // null = main menu
        int page;
        final Map<Integer, Object> slots = new HashMap<>();
        Inventory inv;
        ShopHolder(Category c) { this.category = c; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();
    private Economy vault;
    private final Map<String, Currency> currencies = new LinkedHashMap<>();
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private File dataFile;
    private YamlConfiguration data;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().severe("No Vault economy found. Install Vault + an economy plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        vault = rsp.getProvider();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        loadShop();
        getServer().getPluginManager().registerEvents(this, this);
    }

    // ------------------------------------------------------------ config

    private int[] parseSlots(String s) {
        List<Integer> l = new ArrayList<>();
        for (String part : s.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            if (part.contains("-")) {
                String[] r = part.split("-");
                for (int i = Integer.parseInt(r[0]); i <= Integer.parseInt(r[1]); i++) l.add(i);
            } else l.add(Integer.parseInt(part));
        }
        return l.stream().mapToInt(Integer::intValue).toArray();
    }

    private Entry parseEntry(Object o, int defPrice) {
        if (o instanceof String s) {
            String[] p = s.split(":");
            Material m = Material.matchMaterial(p[0]);
            if (m == null) { getLogger().warning("Unknown material: " + p[0]); return null; }
            int price = p.length > 1 ? Integer.parseInt(p[1]) : defPrice;
            int amt = p.length > 2 ? Integer.parseInt(p[2]) : 1;
            return new Entry(m, null, price, amt, Map.of(), null, null);
        }
        if (o instanceof Map<?, ?> m) {
            Material mat = Material.matchMaterial(String.valueOf(m.get("material")));
            if (mat == null) { getLogger().warning("Unknown material: " + m.get("material")); return null; }
            Map<String, Integer> ench = new LinkedHashMap<>();
            if (m.get("enchants") instanceof Map<?, ?> em)
                em.forEach((k, v) -> ench.put(String.valueOf(k), ((Number) v).intValue()));
            return new Entry(mat,
                    m.get("name") == null ? null : String.valueOf(m.get("name")),
                    m.get("price") == null ? defPrice : ((Number) m.get("price")).intValue(),
                    m.get("amount") == null ? 1 : ((Number) m.get("amount")).intValue(),
                    ench,
                    m.get("command") == null ? null : String.valueOf(m.get("command")),
                    m.get("potion") == null ? null : String.valueOf(m.get("potion")));
        }
        return null;
    }

    private void loadShop() {
        reloadConfig();
        currencies.clear();
        categories.clear();
        ConfigurationSection cs = getConfig().getConfigurationSection("currencies");
        for (String id : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(id);
            currencies.put(id, new Currency(id, c.getString("type", "internal"), c.getString("symbol", "$"), c.getString("label", id)));
        }
        ConfigurationSection cats = getConfig().getConfigurationSection("categories");
        for (String id : cats.getKeys(false)) {
            ConfigurationSection s = cats.getConfigurationSection(id);
            Category c = new Category();
            c.id = id;
            c.title = s.getString("title", id.toUpperCase());
            c.color = TextColor.fromHexString(s.getString("color", "#FFFFFF"));
            Material icon = Material.matchMaterial(s.getString("icon", "STONE"));
            c.icon = icon == null ? Material.STONE : icon;
            c.slot = s.getInt("slot", 0);
            c.main = s.getBoolean("main", true);
            c.rows = s.getInt("rows", 4);
            c.sign = s.getBoolean("sign", false);
            c.currency = currencies.get(s.getString("currency", "money"));
            c.fill = parseSlots(s.getString("fill", "11-15,20-24"));
            c.children = s.getStringList("children");
            ConfigurationSection t = s.getConfigurationSection("tooltip");
            if (t != null) {
                c.tip.tag = t.getString("tag", c.tip.tag);
                c.tip.infoLabel = t.getString("info-label", c.tip.infoLabel);
                c.tip.clickText = t.getString("click", c.tip.clickText);
                c.tip.clickColored = t.getBoolean("click-colored", false);
                c.tip.showCurrency = t.getBoolean("show-currency", true);
                c.tip.hideAttributes = t.getBoolean("hide-attributes", true);
                c.tip.info = t.getStringList("info");
                c.tip.extra = t.getStringList("extra");
            }
            int defPrice = s.getInt("default-price", 100);
            for (Object o : s.getList("items", List.of())) {
                Entry e = parseEntry(o, defPrice);
                if (e != null) c.items.add(e);
            }
            categories.put(id, c);
        }
        for (Category c : categories.values())
            for (String ch : c.children) {
                Category child = categories.get(ch);
                if (child != null) child.parent = c.id;
            }
    }

    // ------------------------------------------------------------ text helpers

    private Component line(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private Component prefix() {
        return Component.text("SHOP ", TextColor.fromHexString("#00BFFF"), TextDecoration.BOLD)
                .append(Component.text("▶ ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false));
    }

    private void msg(Player p, Component body) { p.sendMessage(prefix().append(body)); }
    private String fmt(int n) { return NumberFormat.getIntegerInstance(Locale.US).format(n); }

    private Component clickLine(TextColor color, String text) {
        return line("▶ ", color)
                .append(Component.text("CLICK", color, TextDecoration.BOLD, TextDecoration.UNDERLINED)
                        .decoration(TextDecoration.ITALIC, false))
                .append(line(" " + text, color));
    }

    // ------------------------------------------------------------ menus

    private ItemStack categoryButton(Category c) {
        Tooltip t = c.tip;
        ItemStack it = new ItemStack(c.icon);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(c.title.toUpperCase(), c.color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(t.tag, t.tag.startsWith("[") ? NamedTextColor.GRAY : NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(line(t.infoLabel, c.color));
        for (String s : t.info) lore.add(line("| ", c.color).append(line(s, NamedTextColor.WHITE)));
        if (t.showCurrency && c.currency != null) {
            lore.add(Component.empty());
            lore.add(line("| ", c.color).append(line("Currency: ", NamedTextColor.WHITE)).append(line(c.currency.symbol(), c.color)));
        }
        lore.add(Component.empty());
        lore.add(clickLine(t.clickColored ? c.color : NamedTextColor.YELLOW, t.clickText));
        if (!t.extra.isEmpty()) {
            lore.add(Component.empty());
            for (String s : t.extra) lore.add(AMP.deserialize(s).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        if (t.hideAttributes) meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    private void openMain(Player p) {
        ShopHolder h = new ShopHolder(null);
        h.inv = Bukkit.createInventory(h, 27, Component.text(getConfig().getString("menu-title", "Shop")));
        for (Category c : categories.values()) {
            if (!c.main) continue;
            h.inv.setItem(c.slot, categoryButton(c));
            h.slots.put(c.slot, c);
        }
        p.openInventory(h.inv);
    }

    private ItemStack simple(Material m, String name, TextColor color, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(line(name, color));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(line(s, NamedTextColor.GRAY));
        if (!l.isEmpty()) meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    private void openCategory(Player p, Category c, int page) {
        ShopHolder h = new ShopHolder(c);
        h.page = page;
        int size = c.rows * 9;
        h.inv = Bukkit.createInventory(h, size, Component.text("⚓ Shop | " + c.title + " Menu"));
        int pages = 1;

        if (!c.children.isEmpty()) {
            int i = 0;
            for (String id : c.children) {
                Category child = categories.get(id);
                if (child == null || i >= c.fill.length) continue;
                h.inv.setItem(c.fill[i], categoryButton(child));
                h.slots.put(c.fill[i], child);
                i++;
            }
        } else {
            int per = c.fill.length;
            pages = Math.max(1, (c.items.size() + per - 1) / per);
            page = Math.min(page, pages - 1);
            h.page = page;
            for (int i = page * per; i < Math.min(c.items.size(), (page + 1) * per); i++) {
                Entry e = c.items.get(i);
                int slot = c.fill[i - page * per];
                h.inv.setItem(slot, buildDisplay(c, e));
                h.slots.put(slot, e);
            }
        }

        if (c.sign) h.inv.setItem(4, simple(Material.OAK_SIGN, c.title, c.color));
        int paper = size - 5;
        h.inv.setItem(paper, simple(Material.PAPER, "◀ Back", NamedTextColor.YELLOW,
                pages > 1 ? "Page " + (page + 1) + "/" + pages : "Click to go back"));
        h.slots.put(paper, "BACK");
        if (page > 0) {
            h.inv.setItem(paper - 1, simple(Material.RED_STAINED_GLASS_PANE, "◀ Previous Page", NamedTextColor.RED));
            h.slots.put(paper - 1, "PREV");
        }
        if (page + 1 < pages) {
            h.inv.setItem(paper + 1, simple(Material.GREEN_STAINED_GLASS_PANE, "Next Page ▶", NamedTextColor.GREEN));
            h.slots.put(paper + 1, "NEXT");
        }
        p.openInventory(h.inv);
    }

    private ItemStack buildItem(Entry e) {
        ItemStack it = new ItemStack(e.material(), e.command() == null ? e.amount() : 1);
        ItemMeta meta = it.getItemMeta();
        if (e.name() != null) meta.displayName(AMP.deserialize(e.name()).decoration(TextDecoration.ITALIC, false));
        e.enchants().forEach((k, lvl) -> {
            Enchantment en = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(k.toLowerCase()));
            if (en == null) getLogger().warning("Unknown enchant: " + k);
            else meta.addEnchant(en, lvl, true);
        });
        if (e.potion() != null && meta instanceof PotionMeta pm) {
            PotionType pt = Registry.POTION.get(NamespacedKey.minecraft(e.potion().toLowerCase()));
            if (pt != null) pm.setBasePotionType(pt);
        }
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack buildDisplay(Category c, Entry e) {
        ItemStack it = buildItem(e);
        ItemMeta meta = it.getItemMeta();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(line("| ", c.color).append(line("Price: ", NamedTextColor.WHITE))
                .append(line(fmt(e.price()) + " " + c.currency.symbol(), c.color)));
        if (e.amount() > 1 && e.command() == null)
            lore.add(line("| ", c.color).append(line("Amount: " + e.amount() + "x", NamedTextColor.WHITE)));
        lore.add(Component.empty());
        lore.add(clickLine(NamedTextColor.YELLOW, "to buy"));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    // ------------------------------------------------------------ clicks / buying

    @EventHandler
    public void onClick(InventoryClickEvent ev) {
        if (!(ev.getInventory().getHolder() instanceof ShopHolder h)) return;
        ev.setCancelled(true);
        if (ev.getClickedInventory() != ev.getInventory()) return;
        if (!(ev.getWhoClicked() instanceof Player p)) return;
        Object target = h.slots.get(ev.getSlot());
        if (target == null) return;
        if (target instanceof Category c) openCategory(p, c, 0);
        else if (target instanceof Entry e) buy(p, h.category, e);
        else if ("BACK".equals(target)) {
            Category parent = h.category.parent == null ? null : categories.get(h.category.parent);
            if (parent == null) openMain(p); else openCategory(p, parent, 0);
        }
        else if ("PREV".equals(target)) openCategory(p, h.category, h.page - 1);
        else if ("NEXT".equals(target)) openCategory(p, h.category, h.page + 1);
    }

    private boolean hasSpace(Player p, ItemStack stack) {
        Inventory tmp = Bukkit.createInventory(null, 36);
        ItemStack[] contents = p.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) if (contents[i] != null) tmp.setItem(i, contents[i].clone());
        return tmp.addItem(stack.clone()).isEmpty();
    }

    private void buy(Player p, Category c, Entry e) {
        ItemStack stack = buildItem(e);
        boolean viaCommand = e.command() != null;
        if (!viaCommand && !hasSpace(p, stack)) {
            msg(p, Component.text("You do not have enough inventory space for this purchase.", NamedTextColor.RED));
            return;
        }
        if (balance(p, c.currency) < e.price()) {
            msg(p, Component.text("You do not have enough " + c.currency.label() + " for this purchase.", NamedTextColor.RED));
            return;
        }
        withdraw(p, c.currency, e.price());
        if (viaCommand) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), e.command().replace("{player}", p.getName()));
        else p.getInventory().addItem(stack);

        String name = e.name() != null ? PlainTextComponentSerializer.plainText().serialize(AMP.deserialize(e.name()))
                : prettify(e.material());
        msg(p, Component.text("You bought ", NamedTextColor.GREEN)
                .append(Component.text((viaCommand ? 1 : e.amount()) + "x", NamedTextColor.WHITE))
                .append(Component.text(" "))
                .append(Component.text(name, NamedTextColor.GOLD))
                .append(Component.text(" for ", NamedTextColor.GREEN))
                .append(Component.text(fmt(e.price()) + " " + c.currency.symbol(), NamedTextColor.WHITE))
                .append(Component.text(".", NamedTextColor.GREEN)));
    }

    private String prettify(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String w : m.name().toLowerCase().split("_"))
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        return sb.toString().trim();
    }

    // ------------------------------------------------------------ currencies

    private double balance(Player p, Currency cur) {
        if (cur.type().equals("vault")) return vault.getBalance(p);
        return data.getDouble("balances." + p.getUniqueId() + "." + cur.id(), 0);
    }

    private void withdraw(Player p, Currency cur, int amt) {
        if (cur.type().equals("vault")) vault.withdrawPlayer(p, amt);
        else setBalance(p.getUniqueId(), cur.id(), balance(p, cur) - amt);
    }

    private void setBalance(UUID id, String cur, double v) {
        data.set("balances." + id + "." + cur, v);
        try { data.save(dataFile); } catch (IOException ex) { getLogger().warning("Could not save data.yml"); }
    }

    // ------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("shop")) {
            if (s instanceof Player p) openMain(p);
            return true;
        }
        if (a.length == 0) { s.sendMessage("/shopadmin reload | give|take <player> <currency> <amount>"); return true; }
        if (a[0].equalsIgnoreCase("reload")) { loadShop(); s.sendMessage("Shop reloaded."); return true; }
        if (a.length == 4 && (a[0].equalsIgnoreCase("give") || a[0].equalsIgnoreCase("take"))) {
            Player t = Bukkit.getPlayerExact(a[1]);
            Currency cur = currencies.get(a[2].toLowerCase());
            if (t == null || cur == null || cur.type().equals("vault")) { s.sendMessage("Bad player or currency."); return true; }
            double amt = Double.parseDouble(a[3]);
            double delta = a[0].equalsIgnoreCase("give") ? amt : -amt;
            setBalance(t.getUniqueId(), cur.id(), Math.max(0, balance(t, cur) + delta));
            s.sendMessage("Done.");
            return true;
        }
        return false;
    }
}
