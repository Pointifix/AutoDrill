package autodrill;

import arc.Core;
import arc.Events;
import arc.input.InputProcessor;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.ImageButton;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import autodrill.filler.BridgeDrill;
import autodrill.filler.Direction;
import autodrill.filler.OptimizationDrill;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.game.EventType;
import mindustry.gen.Icon;
import mindustry.mod.Mod;
import mindustry.ui.Styles;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;

import static arc.Core.bundle;
import static mindustry.Vars.ui;

public class AutoDrill extends Mod {
    private static final int buttonSize = 30;

    private boolean enabled = false;

    private Tile selectedTile;
    private Drill selectedDrill;

    private final Table selectTable = new Table(Styles.black3);
    private final Table directionTable = new Table(Styles.black3);
    private ImageButton enableButton;

    private ImageButton mechanicalDrillButton, pneumaticDrillButton, blastDrillButton, laserDrillButton;

    @Override
    public void init() {
        buildSelectTable();
        buildDirectionTable();

        // Settings
        /*Cons<SettingsMenuDialog.SettingsTable> builder = settingsTable -> {
            SettingsMenuDialog.SettingsTable settings = new SettingsMenuDialog.SettingsTable();
            settings.textPref(bundle.get("auto-drill.settings.activation-key"), KeyCode.h.name().toUpperCase(), s -> {
                KeyCode keyCode = Arrays.stream(KeyCode.values()).filter(k -> k.value.equalsIgnoreCase(s)).findFirst().orElse(null);
                Core.settings.put(bundle.get("auto-drill.settings.activation-key"), keyCode == null ? KeyCode.h.name().toUpperCase() : keyCode.name().toUpperCase());
            });
            settings.checkPref(bundle.get("auto-drill.settings.display-toggle-button"), true);
            settings.sliderPref(bundle.get("auto-drill.settings.max-tiles"), 100, 25, 500, 50, i -> i + "");
            settings.sliderPref(bundle.get("auto-drill.settings.optimization-min-ores-laser-drill"), 5, 1, 9, 1, i -> i + "");
            settings.sliderPref(bundle.get("auto-drill.settings.optimization-min-ores-airblast-drill"), 9, 1, 16, 1, i -> i + "");
            settings.sliderPref(bundle.get("auto-drill.settings.optimization-quality"), 2, 1, 10, 1, i -> i + "");
            settings.checkPref(bundle.get("auto-drill.settings.place-water-extractor-and-power-nodes"), true);

            settingsTable.add(settings);
        };
        ui.settings.getCategories().add(new SettingsMenuDialog.SettingsCategory(bundle.get("auto-drill.settings.title"), new TextureRegionDrawable(Core.atlas.find("auto-drill-logo")), builder));
        */

        // Activation
        Core.scene.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, KeyCode keyCode) {
                if (keyCode.equals(KeyCode.h)) {
                    enabled = !enabled;
                    selectTable.visible = false;
                    directionTable.visible = false;
                    enableButton.setChecked(enabled);
                }

                return super.keyDown(event, keyCode);
            }
        });

        // Handling
        Events.on(EventType.TapEvent.class, event -> {
            if (enabled) {
                selectTable.visible = true;
                selectedTile = event.tile;

                updateSelectTable();

                Vec2 v = Core.camera.project(selectedTile.centerX() * Vars.tilesize, (selectedTile.centerY() + 1) * Vars.tilesize);
                selectTable.setPosition(v.x, v.y, Align.bottom);
                directionTable.setPosition(v.x, v.y, Align.bottom);

                Fx.tapBlock.at(selectedTile.getX(), selectedTile.getY());
            }
        });

        ui.hudGroup.fill(t -> {
            enableButton = t.button(new TextureRegionDrawable(Core.atlas.find("auto-drill-logo")), Styles.emptytogglei, () -> {
                enabled = !enabled;
                selectTable.visible = false;
                directionTable.visible = false;
            }).get();
            enableButton.resizeImage(buttonSize);
            enableButton.visible(() -> Core.settings.getBool(bundle.get("auto-drill.settings.display-toggle-button")));

            t.margin(5f);
            t.marginRight(140f + 15f);
            t.top().right();
        });
    }

    private void buildSelectTable() {
        selectTable.update(() -> {
            if (Vars.state.isMenu()) selectTable.visible = false;
        });

        mechanicalDrillButton = selectTable.button(new TextureRegionDrawable(Core.atlas.find("block-mechanical-drill-full")), Styles.defaulti, () -> {
            selectedDrill = (Drill) Blocks.mechanicalDrill;
            selectTable.visible = false;
            directionTable.visible = true;
        }).get();
        mechanicalDrillButton.resizeImage(buttonSize);

        pneumaticDrillButton = selectTable.button(new TextureRegionDrawable(Core.atlas.find("block-pneumatic-drill-full")), Styles.defaulti, () -> {
            selectedDrill = (Drill) Blocks.pneumaticDrill;
            selectTable.visible = false;
            directionTable.visible = true;
        }).get();
        pneumaticDrillButton.resizeImage(buttonSize);

        laserDrillButton = selectTable.button(new TextureRegionDrawable(Core.atlas.find("block-laser-drill-full")), Styles.defaulti, () -> {
            selectedDrill = (Drill) Blocks.laserDrill;
            selectTable.visible = false;
            OptimizationDrill.fill(selectedTile, selectedDrill);
        }).get();
        laserDrillButton.resizeImage(buttonSize);

        blastDrillButton = selectTable.button(new TextureRegionDrawable(Core.atlas.find("block-blast-drill-full")), Styles.defaulti, () -> {
            selectedDrill = (Drill) Blocks.blastDrill;
            selectTable.visible = false;
            OptimizationDrill.fill(selectedTile, selectedDrill);
        }).get();
        blastDrillButton.resizeImage(buttonSize);

        Core.input.addProcessor(new InputProcessor() {
            @Override
            public boolean touchDown(int screenX, int screenY, int pointer, KeyCode button) {
                if (!selectTable.hasMouse()) selectTable.visible = false;

                return InputProcessor.super.touchDown(screenX, screenY, pointer, button);
            }
        });

        selectTable.pack();
        selectTable.act(0);
        Core.scene.root.addChildAt(0, selectTable);
    }

    private void updateSelectTable() {
        selectTable.removeChild(mechanicalDrillButton);
        if (((Drill) Blocks.mechanicalDrill).canMine(selectedTile)) {
            selectTable.add(mechanicalDrillButton);
        }

        selectTable.removeChild(pneumaticDrillButton);
        if (((Drill) Blocks.pneumaticDrill).canMine(selectedTile)) {
            selectTable.add(pneumaticDrillButton);
        }

        selectTable.removeChild(laserDrillButton);
        if (((Drill) Blocks.laserDrill).canMine(selectedTile)) {
            selectTable.add(laserDrillButton);
        }

        selectTable.removeChild(blastDrillButton);
        if (((Drill) Blocks.blastDrill).canMine(selectedTile)) {
            selectTable.add(blastDrillButton);
        }
    }

    private void buildDirectionTable() {
        directionTable.update(() -> {
            if (Vars.state.isMenu()) directionTable.visible = false;
        });

        directionTable.table().get().button(Icon.up, Styles.defaulti, () -> {
            BridgeDrill.fill(selectedTile, selectedDrill, Direction.UP);
            directionTable.visible = false;
        }).get().resizeImage(buttonSize);

        directionTable.row();

        Table row2 = directionTable.table().get();

        row2.button(Icon.left, Styles.defaulti, () -> {
            BridgeDrill.fill(selectedTile, selectedDrill, Direction.LEFT);
            directionTable.visible = false;
        }).get().resizeImage(buttonSize);
        row2.button(Icon.cancel, Styles.defaulti, () -> {
            directionTable.visible = false;
        }).get().resizeImage(buttonSize);
        row2.button(Icon.right, Styles.defaulti, () -> {
            BridgeDrill.fill(selectedTile, selectedDrill, Direction.RIGHT);
            directionTable.visible = false;
        }).get().resizeImage(buttonSize);

        directionTable.row();

        directionTable.table().get().button(Icon.down, Styles.defaulti, () -> {
            BridgeDrill.fill(selectedTile, selectedDrill, Direction.DOWN);
            directionTable.visible = false;
        }).get().resizeImage(buttonSize);

        Core.input.addProcessor(new InputProcessor() {
            @Override
            public boolean touchDown(int screenX, int screenY, int pointer, KeyCode button) {
                if (!directionTable.hasMouse()) directionTable.visible = false;

                return InputProcessor.super.touchDown(screenX, screenY, pointer, button);
            }
        });

        directionTable.pack();
        directionTable.act(0);
        Core.scene.root.addChildAt(0, directionTable);
    }
}
