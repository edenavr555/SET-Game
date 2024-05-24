package bguspl.set.ex;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import bguspl.set.Env;
import bguspl.set.Util;
import java.util.Collections;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import bguspl.set.UtilImpl;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final UtilImpl util;
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    private long lastChange;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    private final BlockingQueue<Player> playersToCheck;

    public Object dealerLock;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        this.util = new UtilImpl(env.config);
        playersToCheck = new ArrayBlockingQueue<>(env.config.players); // can check one set at a time
        this.dealerLock=new Object();
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        for(Player p: players){
            Thread t = new Thread(p, "player-"+p.id);
            t.start();
        }
        table.removingCards.compareAndSet(false, true);
        while (!shouldFinish()) {
            table.beforeWrite();
            placeCardsOnTable();
            table.afterWrite();
            timerLoop();
            updateTimerDisplay(false);
            table.beforeWrite();
            removeAllCardsFromTable();
            table.afterWrite();
        }
        if(!terminate) terminate();
        else{
        for(Player p: players){
            try{
                p.getThread().join();
            }
            catch(InterruptedException e){};
            }
        }
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            table.removingCards.compareAndSet(false, true);
            table.beforeWrite();
            removeCardsFromTable();
            //table.afterWrite();
            if(env.config.turnTimeoutMillis<=0 && shouldFinish()){  // ----- for bonus -----//
                terminate();
            }
            //table.beforeWrite();
            placeCardsOnTable();
            table.afterWrite();
            if(env.config.turnTimeoutMillis<=0){
            while(0==env.util.findSets(Arrays.asList(table.slotToCard), 1).size()){ // ----- for bonus -----//
                table.beforeWrite();
                removeAllCardsFromTable();
                placeCardsOnTable();
                table.afterWrite();
                }
            }
            table.removingCards.compareAndSet(true, false);
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        this.terminate = true;
        for(int i=players.length-1; i>=0; i--){
            synchronized(players[i]){
            players[i].terminate();
            }
            try{
                players[i].getThread().join();
            }
            catch(InterruptedException e){};
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    public void checkMe(Player player){
        if (player.getTokensLeft() == 0){
            try{
                playersToCheck.put(player);
            }
            catch(InterruptedException e){};
        }
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        while(playersToCheck.size()>0){
            Player player = null;
            try{
                player = playersToCheck.take();
            }
            catch(InterruptedException e){};
            synchronized(player){
                if (player.getTokensLeft() == 0){
                    int index = 0;
                    int[] cardsToCheck = new int[env.config.featureSize]; // instead of 3
                    for(int i=0; i<table.tokens.length && index<env.config.featureSize; i++){
                        if(table.tokens[i][player.id]){
                            cardsToCheck[index] = table.slotToCard[i];
                            index++;
                        }
                    }
                    if(util.testSet(cardsToCheck)){
                        player.point();
                        table.removingCards.compareAndSet(false, true);
                        for(int c: cardsToCheck){
                            for(int playerId=0; playerId<env.config.players; playerId++){
                                if(table.removeToken(playerId, table.cardToSlot[c]))
                                    players[playerId].returnToken();
                                players[playerId].removeCardFromQueue(table.cardToSlot[c]);
                            }
                            table.removeCard(table.cardToSlot[c]);
                        }
                    }
                    else{
                        player.penalty();
                    } 
                }
                player.gettingChecked=false;
                player.notifyAll();
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        Collections.shuffle(deck);
        for(int i=0; i<env.config.tableSize; i++){
            if (!deck.isEmpty() && table.getCard(i)==-1) {
                Integer newCard = deck.remove(0);
                table.placeCard(newCard, i);
                updateTimerDisplay(true);
            }
        }
        table.removingCards.compareAndSet(true, false); 
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        synchronized(dealerLock){
            if(env.config.turnTimeoutWarningMillis>=reshuffleTime-System.currentTimeMillis()){
            try {
                dealerLock.wait(1);
            } catch (InterruptedException e) {};
        }
        else{
            try {
                dealerLock.wait(900);
            } 
            catch(InterruptedException e){};
        }
    }
}

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(env.config.turnTimeoutMillis<0)
        {
            return;
        }
        else if(env.config.turnTimeoutMillis==0 && reset){
            lastChange = System.currentTimeMillis();
            env.ui.setCountdown(0, false);
        }
        else if(env.config.turnTimeoutMillis==0 && !reset){
            env.ui.setCountdown(System.currentTimeMillis()-lastChange, false);
        }
        else if(reset){
            reshuffleTime = System.currentTimeMillis()+env.config.turnTimeoutMillis;
            env.ui.setCountdown(Math.max((long)0, (long)reshuffleTime-System.currentTimeMillis()), false);
        }
        else if(env.config.turnTimeoutWarningMillis>=reshuffleTime-System.currentTimeMillis()){
            env.ui.setCountdown(Math.max((long)0, (long)reshuffleTime-System.currentTimeMillis()), true);
        }
        else{
            env.ui.setCountdown(Math.max((long)0, (long)reshuffleTime-System.currentTimeMillis()), false);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        table.removingCards.compareAndSet(false, true);
        for(int slot=0; slot<env.config.tableSize; slot++){
            for(int player=0; player<env.config.players; player++){
                if(table.removeToken(player, slot)){
                    players[player].returnToken();
                }
            }
        }
        for(int slot=0; slot<env.config.tableSize; slot++){
            if(table.slotToCard[slot]!=null)
                deck.add(table.slotToCard[slot]);
        }
        table.clearCards();
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int max = 0;
        int numOfWinners = 0;
        for(Player p: players){
            if(p.score()>max){
                max = p.score();
                numOfWinners = 1;
            }
            else if(p.score()==max)
                numOfWinners++;
        }
        int [] winners = new int[numOfWinners];
        int i=0;
        for(Player p: players){
            if(p.score()==max){
                winners[i] = p.getId();
                i++;
            }
        }
        env.ui.announceWinner(winners);
    }
}

