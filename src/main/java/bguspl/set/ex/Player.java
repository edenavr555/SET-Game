package bguspl.set.ex;

import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * The amount of tokens remaining.
     */
    private int tokensLeft;

    /**
     * The upcoming actions for the player.
     */
    private final ArrayBlockingQueue<Integer> actions;

    private final Dealer dealer;

    private volatile boolean penalty;

    protected volatile boolean gettingChecked;

    private volatile boolean point;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.dealer = dealer;
        this.human = human;
        this.tokensLeft = env.config.featureSize;
        this.actions = new ArrayBlockingQueue<>(env.config.featureSize);
        this.terminate = false;
        this.penalty=false;
        this.gettingChecked=false;
        this.point = false;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();
        while (!terminate) {
            int slot;
            try {
                slot = this.actions.take();
            } catch (InterruptedException e) {
                break;
            };
            table.beforeRead();
            if (!(table.tokens[slot][id]) && tokensLeft > 0 && table.slotToCard[slot] != null){// && !table.removingCards.get()) {
                table.placeToken(id, slot);
                table.afterRead();
                tokensLeft--;
                if (tokensLeft == 0) {
                    gettingChecked=true; //empty actionset?
                    synchronized(this){ 
                        synchronized(dealer.dealerLock){ 
                            dealer.checkMe(this);
                            dealer.dealerLock.notifyAll();
                        }
                        while(gettingChecked && !terminate){
                            try{
                                this.wait();
                            } catch(InterruptedException e){
                                gettingChecked = false;
                                break;
                            };
                        }
                        if(terminate) break;
                    }
                    if(point){
                        playerInPoint();
                        point = false;
                    }
                    else if(penalty){
                        playerInPenalty();
                    }
                    actions.clear();
                } 
            } else if(table.tokens[slot][id]){
                table.removeToken(id, slot);
                tokensLeft++;
                table.afterRead();

            }
            else{
                table.afterRead();
            }
        }
        actions.clear();
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {};
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            Random random = new Random();
            while (!terminate) {
                int randomNumber = random.nextInt(env.config.tableSize);
                keyPressed(randomNumber);
                try {
                    Thread.sleep(0);
                } catch (InterruptedException ignored) {};
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        this.terminate = true;
        if(!human){
            aiThread.interrupt();
        }
        this.playerThread.interrupt();
    }

    /**
     * This method is called when a key is pressed.
     */
    public void keyPressed(int slot) {
        if(!table.removingCards.get() && !penalty && table.slotToCard[slot] != null) {
                try {
                    this.actions.put(slot);
                } catch (InterruptedException e) {};
            }
        } 

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point(){
        this.point = true;
    }

    public void playerInPoint() {
        score++;
        env.ui.setScore(id, score);
        long waitTime = System.currentTimeMillis()+env.config.pointFreezeMillis;
        env.ui.setFreeze(id, waitTime-System.currentTimeMillis());
        try {
            Thread.sleep(env.config.pointFreezeMillis); // Sleep for 1 second
        } catch (InterruptedException e){};
        env.ui.setFreeze(id, waitTime-System.currentTimeMillis());

        // TODO implement
        // maybe need to add more related actions + maybe need to remove the sleep if happens somewhere else
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        penalty=true;
        // TODO implement
    }

    public Thread getThread(){
        return playerThread;
    }

    public void playerInPenalty(){
        long waitTime = System.currentTimeMillis()+env.config.penaltyFreezeMillis;
        env.ui.setFreeze(id, waitTime-System.currentTimeMillis());
        while(System.currentTimeMillis() < waitTime && !terminate){
            try{
                Thread.sleep(990);
            } catch (InterruptedException e){};
            env.ui.setFreeze(id, waitTime-System.currentTimeMillis());
        }
        env.ui.setFreeze(id, waitTime-System.currentTimeMillis());
        this.penalty=false;
    }

    public int score() {
        return score;
    }

    public int getTokensLeft(){
        return tokensLeft;
    }

    public int getId(){
        return id;
    }

    public void returnToken(){
        if(tokensLeft<env.config.featureSize){
            tokensLeft++;
        }
    }

    public void removeCardFromQueue(int slot){
        if(actions.contains(slot))
            actions.remove(slot);
    }

    public Thread getAiThread(){
        return this.aiThread;
    }

    public boolean isHuman(){
        return human;
    }
}

