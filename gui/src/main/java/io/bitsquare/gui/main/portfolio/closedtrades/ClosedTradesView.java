/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.gui.main.portfolio.closedtrades;

import io.bitsquare.gui.common.view.ActivatableViewAndModel;
import io.bitsquare.gui.common.view.FxmlView;
import io.bitsquare.gui.components.HyperlinkWithIcon;
import io.bitsquare.gui.components.PeerInfoIcon;
import io.bitsquare.gui.main.overlays.windows.OfferDetailsWindow;
import io.bitsquare.gui.main.overlays.windows.TradeDetailsWindow;
import io.bitsquare.gui.util.BSFormatter;
import io.bitsquare.gui.util.GUIUtil;
import io.bitsquare.p2p.NodeAddress;
import io.bitsquare.trade.Tradable;
import io.bitsquare.trade.Trade;
import io.bitsquare.trade.offer.OpenOffer;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import org.bitcoinj.core.Coin;

import javax.inject.Inject;

@FxmlView
public class ClosedTradesView extends ActivatableViewAndModel<VBox, ClosedTradesViewModel> {

    @FXML
    TableView<ClosedTradableListItem> tableView;
    @FXML
    TableColumn<ClosedTradableListItem, ClosedTradableListItem> priceColumn, amountColumn, volumeColumn,
            directionColumn, dateColumn, tradeIdColumn, stateColumn, avatarColumn;
    private final BSFormatter formatter;
    private final OfferDetailsWindow offerDetailsWindow;
    private final TradeDetailsWindow tradeDetailsWindow;
    private SortedList<ClosedTradableListItem> sortedList;

    @Inject
    public ClosedTradesView(ClosedTradesViewModel model, BSFormatter formatter, OfferDetailsWindow offerDetailsWindow, TradeDetailsWindow tradeDetailsWindow) {
        super(model);
        this.formatter = formatter;
        this.offerDetailsWindow = offerDetailsWindow;
        this.tradeDetailsWindow = tradeDetailsWindow;
    }

    @Override
    public void initialize() {
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tableView.setPlaceholder(new Label("No closed trades available"));

        setTradeIdColumnCellFactory();
        setDirectionColumnCellFactory();
        setAmountColumnCellFactory();
        setPriceColumnCellFactory();
        setVolumeColumnCellFactory();
        setDateColumnCellFactory();
        setStateColumnCellFactory();
        setAvatarColumnCellFactory();

       /* , , ,
                , , , , avatarColumn;
        */
        tradeIdColumn.setComparator((o1, o2) -> o1.getTradable().getId().compareTo(o2.getTradable().getId()));
        dateColumn.setComparator((o1, o2) -> o1.getTradable().getDate().compareTo(o2.getTradable().getDate()));
        directionColumn.setComparator((o1, o2) -> o1.getTradable().getOffer().getDirection().compareTo(o2.getTradable().getOffer().getDirection()));
        priceColumn.setComparator((o1, o2) -> {
            Tradable tradable = o1.getTradable();
            if (tradable instanceof Trade)
                return GUIUtil.compareTradePrices((Trade) o1.getTradable(), (Trade) o2.getTradable());
            else {
                return GUIUtil.compareOfferPrices(o1.getTradable().getOffer(), o2.getTradable().getOffer());
            }
        });
        volumeColumn.setComparator((o1, o2) -> {
            if (o1.getTradable() instanceof Trade && o2.getTradable() instanceof Trade) {
                return GUIUtil.compareVolumes(((Trade) o1.getTradable()).getTradeVolume(), ((Trade) o2.getTradable()).getTradeVolume());
            } else
                return 0;
        });
        amountColumn.setComparator((o1, o2) -> {
            if (o1.getTradable() instanceof Trade && o2.getTradable() instanceof Trade) {
                Coin amount1 = ((Trade) o1.getTradable()).getTradeAmount();
                Coin amount2 = ((Trade) o2.getTradable()).getTradeAmount();
                return amount1 != null && amount2 != null ? amount1.compareTo(amount2) : 0;
            } else
                return 0;
        });
        avatarColumn.setComparator((o1, o2) -> {
            if (o1.getTradable() instanceof Trade && o2.getTradable() instanceof Trade) {
                NodeAddress tradingPeerNodeAddress1 = ((Trade) o1.getTradable()).getTradingPeerNodeAddress();
                NodeAddress tradingPeerNodeAddress2 = ((Trade) o2.getTradable()).getTradingPeerNodeAddress();
                String address1 = tradingPeerNodeAddress1 != null ? tradingPeerNodeAddress1.hostName : "";
                String address2 = tradingPeerNodeAddress2 != null ? tradingPeerNodeAddress2.hostName : "";
                return address1 != null && address2 != null ? address1.compareTo(address2) : 0;
            } else
                return 0;
        });
        stateColumn.setComparator((o1, o2) -> model.getState(o1).compareTo(model.getState(o2)));

        dateColumn.setSortType(TableColumn.SortType.DESCENDING);
        tableView.getSortOrder().add(dateColumn);
    }

    @Override
    protected void activate() {
        sortedList = new SortedList<>(model.getList());
        sortedList.comparatorProperty().bind(tableView.comparatorProperty());
        tableView.setItems(sortedList);
    }

    @Override
    protected void deactivate() {
        sortedList.comparatorProperty().unbind();
    }


    private void setTradeIdColumnCellFactory() {
        tradeIdColumn.setCellValueFactory((offerListItem) -> new ReadOnlyObjectWrapper<>(offerListItem.getValue()));
        tradeIdColumn.setCellFactory(
                new Callback<TableColumn<ClosedTradableListItem, ClosedTradableListItem>, TableCell<ClosedTradableListItem,
                        ClosedTradableListItem>>() {

                    @Override
                    public TableCell<ClosedTradableListItem, ClosedTradableListItem> call(TableColumn<ClosedTradableListItem,
                            ClosedTradableListItem> column) {
                        return new TableCell<ClosedTradableListItem, ClosedTradableListItem>() {
                            private HyperlinkWithIcon field;

                            @Override
                            public void updateItem(final ClosedTradableListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty) {
                                    field = new HyperlinkWithIcon(model.getTradeId(item), true);
                                    field.setOnAction(event -> {
                                        Tradable tradable = item.getTradable();
                                        if (tradable instanceof Trade)
                                            tradeDetailsWindow.show((Trade) tradable);
                                        else if (tradable instanceof OpenOffer)
                                            offerDetailsWindow.show(tradable.getOffer());
                                    });
                                    field.setTooltip(new Tooltip("Open popup for details"));
                                    setGraphic(field);
                                } else {
                                    setGraphic(null);
                                    if (field != null)
                                        field.setOnAction(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setDateColumnCellFactory() {
        dateColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        dateColumn.setCellFactory(
                new Callback<TableColumn<ClosedTradableListItem, ClosedTradableListItem>, TableCell<ClosedTradableListItem,
                        ClosedTradableListItem>>() {
                    @Override
                    public TableCell<ClosedTradableListItem, ClosedTradableListItem> call(
                            TableColumn<ClosedTradableListItem, ClosedTradableListItem> column) {
                        return new TableCell<ClosedTradableListItem, ClosedTradableListItem>() {
                            @Override
                            public void updateItem(final ClosedTradableListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null)
                                    setText(model.getDate(item));
                                else
                                    setText("");
                            }
                        };
                    }
                });
    }

    private void setStateColumnCellFactory() {
        stateColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        stateColumn.setCellFactory(
                new Callback<TableColumn<ClosedTradableListItem, ClosedTradableListItem>, TableCell<ClosedTradableListItem,
                        ClosedTradableListItem>>() {
                    @Override
                    public TableCell<ClosedTradableListItem, ClosedTradableListItem> call(
                            TableColumn<ClosedTradableListItem, ClosedTradableListItem> column) {
                        return new TableCell<ClosedTradableListItem, ClosedTradableListItem>() {
                            @Override
                            public void updateItem(final ClosedTradableListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null)
                                    setText(model.getState(item));
                                else
                                    setText("");
                            }
                        };
                    }
                });
    }

    private TableColumn<ClosedTradableListItem, ClosedTradableListItem> setAvatarColumnCellFactory() {
        avatarColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        avatarColumn.setCellFactory(
                new Callback<TableColumn<ClosedTradableListItem, ClosedTradableListItem>, TableCell<ClosedTradableListItem,
                        ClosedTradableListItem>>() {
                    @Override
                    public TableCell<ClosedTradableListItem, ClosedTradableListItem> call(TableColumn<ClosedTradableListItem, ClosedTradableListItem> column) {
                        return new TableCell<ClosedTradableListItem, ClosedTradableListItem>() {

                            @Override
                            public void updateItem(final ClosedTradableListItem newItem, boolean empty) {
                                super.updateItem(newItem, empty);

                                if (newItem != null && !empty && newItem.getTradable() instanceof Trade) {

                                    int numPastTrades = model.getNumPastTrades(newItem.getTradable());
                                    String hostName = ((Trade) newItem.getTradable()).getTradingPeerNodeAddress().hostName;
                                    Node identIcon = new PeerInfoIcon(hostName, "Trading peers onion address: " + hostName, numPastTrades);
                                    setPadding(new Insets(-2, 0, -2, 0));
                                    if (identIcon != null)
                                        setGraphic(identIcon);
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
        return avatarColumn;
    }

    private void setAmountColumnCellFactory() {
        amountColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        amountColumn.setCellFactory(
                new Callback<TableColumn<ClosedTradableListItem, ClosedTradableListItem>, TableCell<ClosedTradableListItem,
                        ClosedTradableListItem>>() {
                    @Override
                    public TableCell<ClosedTradableListItem, ClosedTradableListItem> call(
                            TableColumn<ClosedTradableListItem, ClosedTradableListItem> column) {
                        return new TableCell<ClosedTradableListItem, ClosedTradableListItem>() {
                            @Override
                            public void updateItem(final ClosedTradableListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                setText(model.getAmount(item));
                            }
                        };
                    }
                });
    }

    private void setPriceColumnCellFactory() {
        priceColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        priceColumn.setCellFactory(
                new Callback<TableColumn<ClosedTradableListItem, ClosedTradableListItem>, TableCell<ClosedTradableListItem,
                        ClosedTradableListItem>>() {
                    @Override
                    public TableCell<ClosedTradableListItem, ClosedTradableListItem> call(
                            TableColumn<ClosedTradableListItem, ClosedTradableListItem> column) {
                        return new TableCell<ClosedTradableListItem, ClosedTradableListItem>() {
                            @Override
                            public void updateItem(final ClosedTradableListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                setText(model.getPrice(item));
                            }
                        };
                    }
                });
    }

    private void setVolumeColumnCellFactory() {
        volumeColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        volumeColumn.setCellFactory(
                new Callback<TableColumn<ClosedTradableListItem, ClosedTradableListItem>, TableCell<ClosedTradableListItem,
                        ClosedTradableListItem>>() {
                    @Override
                    public TableCell<ClosedTradableListItem, ClosedTradableListItem> call(
                            TableColumn<ClosedTradableListItem, ClosedTradableListItem> column) {
                        return new TableCell<ClosedTradableListItem, ClosedTradableListItem>() {
                            @Override
                            public void updateItem(final ClosedTradableListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null)
                                    setText(model.getVolume(item));
                                else
                                    setText("");
                            }
                        };
                    }
                });
    }

    private void setDirectionColumnCellFactory() {
        directionColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        directionColumn.setCellFactory(
                new Callback<TableColumn<ClosedTradableListItem, ClosedTradableListItem>, TableCell<ClosedTradableListItem,
                        ClosedTradableListItem>>() {
                    @Override
                    public TableCell<ClosedTradableListItem, ClosedTradableListItem> call(
                            TableColumn<ClosedTradableListItem, ClosedTradableListItem> column) {
                        return new TableCell<ClosedTradableListItem, ClosedTradableListItem>() {
                            @Override
                            public void updateItem(final ClosedTradableListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                setText(model.getDirectionLabel(item));
                            }
                        };
                    }
                });
    }
}

